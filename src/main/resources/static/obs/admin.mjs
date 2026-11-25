import {obsSession,bindObsPage,obsPageCurrent,assertObsPage} from '../auth/obs-session.mjs';
import {mountScoreboardAdmin,bindScoreboardSession} from './scoreboard-admin.mjs';
import {mountInfographicsSettings} from './infographics-settings-admin.mjs';
import {mountActiveLinks} from './active-links-admin.mjs';
import {readStaffJson,watchStaffJson} from './staff-reader.mjs';
import {validateSnapshot} from './scoreboard-model.mjs';
import {teamAccent,imageAccent} from './colors.mjs';

const $=id=>document.getElementById(id), message=text=>$('save-status').textContent=text;
const configPath='/api/v1/obs/config', previewPath='/api/v1/obs/previews/scoreboard/';
const configFields=['maxRows','ttlSeconds','corner','backgroundColor','textColor'];
const lifetime=new AbortController(), accentDrafts=new Map();
let config, match=null, binding, teamsKey, auto={}, photos, globalDirty=false, saving=false, disposed=false;
let page=0, loadGeneration=0, stopConfig=()=>{}, stopMatch=()=>{};

function controls() {
 $('log-fields').disabled=disposed||!config||saving;
 $('team-colors').disabled=disposed||!config||!match||saving;
 $('save').disabled=disposed||!config||saving;
 $('test').disabled=disposed||!match;
}

function end(clearDraft=false) {
 if(disposed)return;
 disposed=true;stopConfig();stopMatch();lifetime.abort();photos?.abort();
 if(clearDraft){accentDrafts.clear();$('teams').replaceChildren();}
 controls();$('preview').src='about:blank';
 $('links-matches').replaceChildren();
 for(const id of ['links-mode','links-match','links-open','links-matches','previous','next'])$(id).disabled=true;
 $('auth-status').textContent='Сессия изменилась. Введённые данные остаются локально; откройте страницу заново.';
 $('status').textContent='Редакторы остановлены. Изменения не отправляются.';
}
addEventListener('pagehide',()=>end(true),{once:true});

function accent(id) {
 const draft=accentDrafts.get(match?.matchId);
 return draft?.has(id)?draft.get(id):config?.teamColors[id];
}
function updateColors() {
 for(const row of $('teams').children) {
  const input=row.querySelector('input'), id=input.dataset.logColor, color=accent(id);
  input.value=teamAccent(id,{[id]:color},auto,$('backgroundColor').value);
  row.querySelector('button').disabled=!color;
 }
 controls();
}
function renderTeams() {
 $('teams').replaceChildren();auto={};photos?.abort();photos=new AbortController();
 const signal=photos.signal, matchId=match?.matchId;
 for(const {teamId,teamName,photoUrl} of match?.teams??[]) {
  function edit(color) {
   if(signal.aborted||disposed)return;
   const draft=accentDrafts.get(matchId)??new Map();
   draft.set(teamId,color);accentDrafts.set(matchId,draft);
   updateColors();
  }
  const row=document.createElement('div'),label=document.createElement('label');
  label.textContent=teamName;
  const input=document.createElement('input');
  input.type='color';input.dataset.logColor=teamId;input.setAttribute('aria-label',`Акцент журнала · ${teamName}`);
  input.addEventListener('input',()=>edit(input.value));label.append(input);
  const reset=document.createElement('button');reset.type='button';reset.textContent='Авто';
  reset.setAttribute('aria-label',`Автоцвет журнала · ${teamName}`);
  reset.addEventListener('click',()=>edit(null));
  row.append(label,' ',reset);$('teams').append(row);
  void imageAccent(photoUrl,signal).then(color=>{
   if(signal.aborted||!obsPageCurrent())return;
   auto[teamId]=color;updateColors();
  });
 }
 updateColors();
}

function showMatch(value, emptyText='Нет матча для трансляции.') {
 const oldId=match?.matchId;match=value;
 const id=match?.matchId;
 $('editor-context').dataset.matchId=id??'';
 $('editor-context').textContent=match?`${match.teams.map(team=>team.teamName).join(' — ')} · ${match.status}`:emptyText;
 if(oldId!==id){
  mountScoreboardAdmin(id);
  $('preview').src=id?`/obs-editor/event-log.html?matchId=${id}`:'about:blank';
  $('status').textContent=id?'Подключение журнала событий…':emptyText;
 }
 const key=JSON.stringify([id,match?.teams.map(team=>[team.teamId,team.teamName,team.photoUrl])]);
 if(key!==teamsKey){teamsKey=key;renderTeams();}
}
function watchMatch(id) {
 if(binding===id)return;
 binding=id;stopMatch();showMatch(null,'Проверка текущего матча…');
 stopMatch=watchStaffJson(obsSession,previewPath+(id??'auto'),value=>{
  const snapshot=id?value:value.snapshot;
  if(snapshot)validateSnapshot(snapshot,id??snapshot.matchId);
  showMatch(snapshot);
 },text=>{
  if(text!=='Синхронизировано')showMatch(null,'Матч недоступен. Повтор…');
 },end);
}

function showConfig(value) {
 config=value;
 if(!globalDirty){
  for(const name of configFields)$(name).value=value[name];
  document.querySelectorAll('[name=category]').forEach(input=>input.checked=value.categories.includes(input.value));
 }
 updateColors();
}
function watchConfig() {
 stopConfig();
 stopConfig=watchStaffJson(obsSession,configPath,showConfig,text=>{
  if(text!=='Синхронизировано'&&!saving)message(text);
 },end);
}
$('log-fields').addEventListener('input',()=>{globalDirty=true;updateColors();});
$('settings').addEventListener('submit',async event=>{
 event.preventDefault();if(!config||disposed||saving)return;
 const id=match?.matchId;
 const teamColors=Object.fromEntries((match?.teams??[]).map(({teamId})=>[teamId,accent(teamId)]).filter(([,color])=>color));
 const value={categories:[...document.querySelectorAll('[name=category]:checked')].map(input=>input.value),
 maxRows:Number($('maxRows').value),ttlSeconds:Number($('ttlSeconds').value),corner:$('corner').value,
 backgroundColor:$('backgroundColor').value,textColor:$('textColor').value,teamColors};
 saving=true;controls();
 try {
  assertObsPage();
  const query=id?`matchId=${encodeURIComponent(id)}`:'preserveTeamColors=true';
  const response=await obsSession.protectedFetch(`${configPath}?${query}`,{method:'PUT',
   signal:AbortSignal.any([lifetime.signal,AbortSignal.timeout(5000)]),headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
  if(!response.ok){const error=await response.json();throw Error(error.detail??`HTTP ${response.status}`);}
  const saved=await response.json();assertObsPage();if(disposed)return;
  globalDirty=false;accentDrafts.delete(id);showConfig(saved);watchConfig();
  message(id?`Сохранено для матча ${id} и общих настроек журнала.`:'Общие настройки журнала сохранены.');
 }catch(error){if(!disposed)message(error.message);}
 finally{saving=false;controls();}
});

async function loadMatches() {
 const selected=++loadGeneration;
 try {
  const data=await readStaffJson(obsSession,`/api/v1/matches?page=${page}&size=20&sortBy=PLANNED_START_TIMESTAMP&sortDirection=DESC`,lifetime.signal);
  if(disposed||selected!==loadGeneration)return;
  const picker=$('links-matches');
  picker.replaceChildren(new Option('Выберите матч',''));
  for(const item of data.content)picker.add(new Option(`${item.teams.map(t=>t.teamName).join(' — ')} · ${item.status}`,item.id));
  picker.value=binding??'';
  $('page').textContent=String(page+1);$('previous').disabled=page===0;$('next').disabled=page+1>=data.totalPages;
 }catch(error){if(!disposed&&selected===loadGeneration)$('status').textContent=`Список матчей · ${error.message}`;}
}
$('previous').onclick=()=>{page=Math.max(0,page-1);void loadMatches();};
$('next').onclick=()=>{page++;void loadMatches();};
$('test').onclick=()=>{if(match&&!disposed&&obsPageCurrent())$('preview').contentWindow?.postMessage({type:'obs-test'},location.origin);};
addEventListener('message',event=>{
 if(match&&!disposed&&obsPageCurrent()&&event.origin===location.origin&&event.source===$('preview').contentWindow&&event.data?.type==='obs-status')
  $('status').textContent=event.data.message;
});
try {
 await obsSession.bootstrap('OBS_ADMIN');
 if(disposed){obsSession.dispose();throw Error('Страница закрыта');}
 bindObsPage();bindScoreboardSession();
 obsSession.subscribe(()=>{if(!obsPageCurrent())end();});
 mountActiveLinks(obsSession,{onContext:watchMatch});mountInfographicsSettings(obsSession);
 $('auth-status').textContent='Организатор · защищённая сессия проверена.';
 for(const id of ['links-mode','links-match','links-open','links-matches'])$(id).disabled=false;
 watchConfig();void loadMatches();
} catch(error) {
 if(disposed)throw error;
 $('auth-status').textContent=error.message;
 message('Войдите и откройте страницу заново.');throw error;
}
