import {watchScoreboard} from './scoreboard-client.mjs';
import {defaultProfile} from './scoreboard-model.mjs';
import {teamAccent,imageAccent} from './colors.mjs';
const $=id=>document.getElementById(id),uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
let dispose=()=>{},generation=0,matchId=null,snapshot=null,draft=null,dirty=false,saving=false,fieldsKey=null;
let auto={};
const message=text=>{$('scoreboard-status').textContent=text;};
const postDraft=()=>{if(draft)$('scoreboard-preview').contentWindow?.postMessage({type:'scoreboard-draft',profile:draft},location.origin);};
function fields() {
 if(!snapshot||!draft)return;
 $('scoreboard-fields').disabled=saving;
 const key=JSON.stringify([snapshot.teams.map(t=>[t.teamId,t.teamName]),draft]);
 if(key===fieldsKey)return;fieldsKey=key;
 $('scoreboard-teams').replaceChildren();
 for(const team of snapshot.teams) {
  const row=document.createElement('div');row.className='scoreboard-edit-team';
  const label=document.createElement('label');label.textContent=`Эфирное название · ${team.teamName}`;
  const name=document.createElement('input');name.type='text';name.maxLength=80;name.placeholder=team.teamName;
  name.dataset.scoreboardName=team.teamId;name.value=draft.shortNames[team.teamId]??'';
  name.addEventListener('input',()=>{if(name.value.trim())draft.shortNames[team.teamId]=name.value;else delete draft.shortNames[team.teamId];dirty=true;postDraft();});
  label.append(name);
  const colorLabel=document.createElement('label');colorLabel.textContent='Цвет ';
  const color=document.createElement('input');color.type='color';color.dataset.scoreboardColor=team.teamId;color.value=teamAccent(team.teamId,draft.teamColors,auto);
  color.addEventListener('input',()=>{draft.teamColors[team.teamId]=color.value;dirty=true;postDraft();});colorLabel.append(color);
  const reset=document.createElement('button');reset.type='button';reset.textContent='Авто';
  reset.onclick=()=>{delete draft.teamColors[team.teamId];dirty=true;fields();postDraft();};
  row.append(label,colorLabel,reset);$('scoreboard-teams').append(row);
 }
 $('scoreboard-order').textContent=draft.swapped?'Порядок: обратный':'Порядок: как в матче';
}
export function mountScoreboardAdmin(id) {
 dispose();const selected=++generation;matchId=id;snapshot=null;draft=null;dirty=false;saving=false;fieldsKey=null;auto={};
 $('scoreboard-settings').hidden=false;$('scoreboard-fields').disabled=true;$('scoreboard-url').value='';
 $('scoreboard-teams').replaceChildren();$('scoreboard-preview').src='about:blank';
 if(!uuid.test(id)){message('Введите корректный UUID матча');return;}
 $('scoreboard-url').value=new URL(`/obs-scoreboard.html?matchId=${id}`,location.origin).href;
 $('scoreboard-preview').src=`/obs-scoreboard.html?matchId=${id}&preview=1`;message('Подключение табло…');
 dispose=watchScoreboard(id,value=>{
  if(selected!==generation)return;
  const oldPhotos=new Map(snapshot?.teams.map(t=>[t.teamId,t.photoUrl]));snapshot=value;
  if(!dirty&&!saving){draft=structuredClone(value.profile);fields();}
  postDraft();
  for(const team of value.teams)if(oldPhotos.get(team.teamId)!==team.photoUrl){
   const photo=team.photoUrl;
   void imageAccent(photo).then(color=>{
    if(selected!==generation||snapshot.teams.find(t=>t.teamId===team.teamId)?.photoUrl!==photo)return;
    auto[team.teamId]=color;
    // Avoid replacing a text input under the operator's cursor for an image-only result.
    const input=$('scoreboard-teams').querySelector(`[data-scoreboard-color="${team.teamId}"]`);
    if(input&&!draft.teamColors[team.teamId])input.value=teamAccent(team.teamId,draft.teamColors,auto);
   });
  }
 },text=>{if(selected===generation)message(dirty?`${text} · Есть несохранённые изменения`:text);});
}
$('scoreboard-preview').addEventListener('load',postDraft);
$('scoreboard-swap').onclick=()=>{if(!draft)return;draft.swapped=!draft.swapped;dirty=true;fields();postDraft();};
$('scoreboard-reset').onclick=()=>{if(!snapshot)return;draft=defaultProfile();dirty=true;fields();postDraft();message('Сброс только в предпросмотре. Сохраните для эфира.');};
$('scoreboard-form').addEventListener('submit',async event=>{
 event.preventDefault();if(!draft||saving)return;
 const selected=generation,id=matchId,value=structuredClone(draft);saving=true;$('scoreboard-fields').disabled=true;
 try {
  const response=await fetch(`/api/v1/obs/scoreboard/${encodeURIComponent(id)}/profile`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(value),signal:AbortSignal.timeout(5000)});
  if(!response.ok){const problem=await response.json();throw Error(problem.detail??`HTTP ${response.status}`);}
  const saved=await response.json();if(selected!==generation)return;
  draft=saved;dirty=false;fieldsKey=null;message('Профиль сохранён для всех источников этого матча.');postDraft();
 }catch(error){if(selected===generation)message(`Не сохранено: ${error.message}`);}
 finally{if(selected===generation){saving=false;fields();}}
});
$('scoreboard-copy').onclick=async()=>{
 if(!$('scoreboard-url').value)return;
 try{await navigator.clipboard.writeText($('scoreboard-url').value);message('Ссылка табло скопирована');}
 catch{$('scoreboard-url').select();message('Скопируйте выделенную ссылку табло');}
};
addEventListener('message',event=>{
 if(event.origin===location.origin&&event.source===$('scoreboard-preview').contentWindow&&event.data?.type==='scoreboard-status')
  message(dirty?`${event.data.message} · Есть несохранённые изменения`:event.data.message);
});
addEventListener('pagehide',()=>{generation++;dispose();},{once:true});
