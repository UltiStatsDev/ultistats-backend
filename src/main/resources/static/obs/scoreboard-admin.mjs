import {obsSession,obsPageCurrent,assertObsPage} from '../auth/obs-session.mjs';
import {watchStaffJson} from './staff-reader.mjs';
import {defaultProfile,validateSnapshot} from './scoreboard-model.mjs';
import {teamAccent,imageAccent} from './colors.mjs';
const $=id=>document.getElementById(id),uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
let dispose=()=>{},generation=0,matchId=null,snapshot=null,draft=null,fieldsKey=null;
const drafts=new Map(),pending=new Set(),writes=new AbortController();
const dirty=()=>drafts.has(matchId);
const edit=()=>drafts.set(matchId,draft);
let frozen=false,auto={},images=new AbortController();
function end(clearDraft=false) {
 frozen=true;generation++;dispose();images.abort();writes.abort();snapshot=null;auto={};
 if(clearDraft){drafts.clear();draft=null;$('scoreboard-teams').replaceChildren();}
 $('scoreboard-fields').disabled=true;$('scoreboard-preview').src='about:blank';
 message('Сессия изменилась. Черновик остаётся локально; изменения не отправляются.');
}
const message=text=>{$('scoreboard-status').textContent=text;};
const postDraft=()=>{if(!frozen&&draft&&obsPageCurrent())$('scoreboard-preview').contentWindow?.postMessage({type:'scoreboard-draft',profile:draft},location.origin);};
export function bindScoreboardSession(){obsSession.subscribe(()=>{if(!obsPageCurrent())end();});}
function fields() {
 if(!snapshot||!draft)return;
 $('scoreboard-fields').disabled=pending.has(matchId)||!obsPageCurrent();
 const key=JSON.stringify([snapshot.teams.map(t=>[t.teamId,t.teamName]),draft]);
 if(key===fieldsKey)return;fieldsKey=key;
 $('scoreboard-teams').replaceChildren();
 for(const team of snapshot.teams) {
  const row=document.createElement('div');row.className='scoreboard-edit-team';
  const label=document.createElement('label');label.textContent=`Эфирное название · ${team.teamName}`;
  const name=document.createElement('input');name.type='text';name.maxLength=80;name.placeholder=team.teamName;
  name.dataset.scoreboardName=team.teamId;name.value=draft.shortNames[team.teamId]??'';
  name.addEventListener('input',()=>{if(name.value.trim())draft.shortNames[team.teamId]=name.value;else delete draft.shortNames[team.teamId];edit();postDraft();});
  label.append(name);
  const colorLabel=document.createElement('label');colorLabel.textContent='Цвет ';
  const color=document.createElement('input');color.type='color';color.dataset.scoreboardColor=team.teamId;color.value=teamAccent(team.teamId,draft.teamColors,auto);
  color.addEventListener('input',()=>{draft.teamColors[team.teamId]=color.value;edit();postDraft();});colorLabel.append(color);
  const reset=document.createElement('button');reset.type='button';reset.textContent='Авто';
  reset.onclick=()=>{delete draft.teamColors[team.teamId];edit();fields();postDraft();};
  row.append(label,colorLabel,reset);$('scoreboard-teams').append(row);
 }
 $('scoreboard-order').textContent=draft.swapped?'Порядок: обратный':'Порядок: как в матче';
}
export function mountScoreboardAdmin(id) {
 if(frozen||!obsPageCurrent())return;
 dispose();images.abort();images=new AbortController();const selected=++generation;matchId=id;snapshot=null;draft=null;fieldsKey=null;auto={};
 $('scoreboard-settings').hidden=!id;$('scoreboard-fields').disabled=true;
 $('scoreboard-teams').replaceChildren();$('scoreboard-preview').src='about:blank';
 if(!id)return;
 if(!uuid.test(id)){message('Введите корректный UUID матча');return;}
 $('scoreboard-preview').src=`/obs-editor/scoreboard.html?matchId=${id}`;message('Подключение табло…');
 watchProfile(id,selected);
}
function watchProfile(id,selected){
 dispose();
 dispose=watchStaffJson(obsSession,`/api/v1/obs/scoreboard/${encodeURIComponent(id)}`,value=>{
  if(selected!==generation||!obsPageCurrent())return;
  validateSnapshot(value,id.toLowerCase());
  const oldPhotos=new Map(snapshot?.teams.map(t=>[t.teamId,t.photoUrl]));snapshot=value;
  const stored=drafts.get(id);
  draft=stored??structuredClone(value.profile);
  if(stored){
   const members=new Set(value.teams.map(team=>team.teamId));
   for(const overrides of [stored.shortNames,stored.teamColors]){
    for(const teamId of Object.keys(overrides))if(!members.has(teamId))delete overrides[teamId];
   }
  }
  fields();
  postDraft();
  for(const team of value.teams)if(oldPhotos.get(team.teamId)!==team.photoUrl){
   const photo=team.photoUrl;
   void imageAccent(photo,images.signal).then(color=>{
    if(selected!==generation||!obsPageCurrent()||!snapshot||snapshot.teams.find(t=>t.teamId===team.teamId)?.photoUrl!==photo)return;
    auto[team.teamId]=color;
    // Avoid replacing a text input under the operator's cursor for an image-only result.
    const input=$('scoreboard-teams').querySelector(`[data-scoreboard-color="${team.teamId}"]`);
    if(input&&!draft.teamColors[team.teamId])input.value=teamAccent(team.teamId,draft.teamColors,auto);
   });
  }
 },text=>{if(selected===generation)message(dirty()?`${text} · Черновик остаётся на странице`:text);},end);
}
$('scoreboard-preview').addEventListener('load',postDraft);
$('scoreboard-swap').onclick=()=>{if(!draft)return;draft.swapped=!draft.swapped;edit();fields();postDraft();};
$('scoreboard-reset').onclick=()=>{if(!snapshot)return;draft=defaultProfile();edit();fields();postDraft();message('Сброс только в предпросмотре. Сохраните для эфира.');};
$('scoreboard-form').addEventListener('submit',async event=>{
 event.preventDefault();if(frozen||!snapshot||!draft||pending.has(matchId))return;
 const id=matchId,value=structuredClone(draft);pending.add(id);$('scoreboard-fields').disabled=true;
 try {
  assertObsPage();
  const response=await obsSession.protectedFetch(`/api/v1/obs/scoreboard/${encodeURIComponent(id)}/profile`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal:AbortSignal.any([writes.signal,AbortSignal.timeout(5000)])});
  if(!response.ok){const problem=await response.json();throw Error(problem.detail??`HTTP ${response.status}`);}
  const saved=await response.json();assertObsPage();if(frozen)return;
  drafts.delete(id);
  if(id!==matchId)return;
  draft=saved;fieldsKey=null;watchProfile(id,generation);message('Профиль сохранён для всех источников этого матча.');postDraft();
 }catch(error){if(!frozen&&id===matchId)message(`Результат сохранения не подтверждён: ${error.message} Черновик остаётся на странице. Проверьте актуальный профиль перед повтором.`);}
 finally{pending.delete(id);if(!frozen&&id===matchId)fields();}
});
addEventListener('message',event=>{
 if(!frozen&&snapshot&&obsPageCurrent()&&event.origin===location.origin&&event.source===$('scoreboard-preview').contentWindow&&event.data?.type==='scoreboard-status')
  {message(dirty()?`${event.data.message} · Черновик остаётся на странице`:event.data.message);if(event.data.message==='Синхронизировано')postDraft();}
});
addEventListener('pagehide',()=>end(true),{once:true});
