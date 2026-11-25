import {obsSession,bindObsPage,obsPageCurrent,assertObsPage} from '../auth/obs-session.mjs';
import {mountScoreboardAdmin,bindScoreboardSession} from './scoreboard-admin.mjs';
import {mountInfographicsSettings} from './infographics-settings-admin.mjs';
import {mountActiveLinks} from './active-links-admin.mjs';
import {readStaffJson,watchStaffJson} from './staff-reader.mjs';
import {teamAccent,imageAccent} from './colors.mjs';
const $=id=>document.getElementById(id),uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
let openGeneration=0;
let config=null,draftColors={},teams=[],auto={},page=0,dirty=false,disposed=false,stop=()=>{};
const images=new AbortController();
const readJson=async path=>{assertObsPage();if(disposed)throw Error('Страница закрыта');const value=await readStaffJson(obsSession,path,images.signal);assertObsPage();if(disposed)throw Error('Страница закрыта');return value;};
function end(clearDraft=false) {
 if(disposed)return;disposed=true;stop();images.abort();openGeneration++;
 config=null;teams=[];auto={};if(clearDraft){draftColors={};$('teams').replaceChildren();}
 $('settings').querySelectorAll('input,select,button').forEach(el=>{el.disabled=true;});
 $('preview').src='about:blank';$('save').disabled=true;$('test').disabled=true;
 $('matches').replaceChildren();$('links-matches').replaceChildren();
 $('auth-status').textContent='Сессия изменилась. Введённые данные остаются локально; откройте страницу заново.';
 $('status').textContent='Редакторы остановлены. Изменения не отправляются.';
}
addEventListener('pagehide',()=>end(true),{once:true});
const message=text=>$('save-status').textContent=text;
try {await obsSession.bootstrap('OBS_ADMIN');if(disposed){obsSession.dispose();throw Error('Страница закрыта');}bindObsPage();bindScoreboardSession();mountActiveLinks(obsSession);mountInfographicsSettings(obsSession);$('auth-status').textContent='Организатор: защищённая сессия проверена.';}
catch(error){if(disposed)throw error;$('auth-status').textContent=error.message;message('Изменения не отправляются. Войдите и откройте страницу заново.');throw error;}
obsSession.subscribe(()=>{if(!obsPageCurrent())end();});
function renderTeams() {
 $('teams').replaceChildren();
 const ids=new Set([...teams.map(t=>t.teamId),...Object.keys(draftColors)]);
 for(const id of ids) {
  const team=teams.find(t=>t.teamId===id),row=document.createElement('div'),label=document.createElement('label');
  label.textContent=team?.teamName??id;const input=document.createElement('input');input.type='color';input.value=teamAccent(id,draftColors,auto,config?.backgroundColor);
  input.setAttribute('aria-label',`Цвет ${team?.teamName??id}`);
  input.addEventListener('input',()=>{draftColors[id]=input.value;dirty=true;});
  const reset=document.createElement('button');reset.type='button';reset.textContent='Авто';
  reset.addEventListener('click',()=>{delete draftColors[id];dirty=true;renderTeams();});
  row.append(label,input,' ',reset);$('teams').append(row);
 }
}
stop=watchStaffJson(obsSession,'/api/v1/obs/config',value=>{
 config=value;$('save').disabled=!obsPageCurrent();
 if(dirty){message('Настройки изменились. Сохранение заменит общие настройки вашим вариантом.');return;}
 for(const name of ['maxRows','ttlSeconds','corner','backgroundColor','textColor'])$(name).value=value[name];
 document.querySelectorAll('[name=category]').forEach(input=>input.checked=value.categories.includes(input.value));
 draftColors={...value.teamColors};renderTeams();
},text=>message(text),end);
$('settings').addEventListener('input',()=>dirty=true);
$('settings').addEventListener('submit',async event=>{
 event.preventDefault();if(!config||disposed)return;
 const value={categories:[...document.querySelectorAll('[name=category]:checked')].map(input=>input.value),
 maxRows:Number($('maxRows').value),ttlSeconds:Number($('ttlSeconds').value),corner:$('corner').value,
 backgroundColor:$('backgroundColor').value,textColor:$('textColor').value,teamColors:draftColors};
 $('save').disabled=true;
 try {
  assertObsPage();
  const response=await obsSession.protectedFetch('/api/v1/obs/config',{method:'PUT',signal:AbortSignal.any([images.signal,AbortSignal.timeout(5000)]),headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
  if(!response.ok){const error=await response.json();throw Error(error.detail??`HTTP ${response.status}`);}
  const saved=await response.json();assertObsPage();if(disposed)return;config=saved;dirty=false;message('Сохранено для всех OBS-источников.');
 }catch(error){if(!disposed)message(error.message);}finally{$('save').disabled=disposed||!obsPageCurrent();}
});
$('add-team').onclick=()=>{const id=$('team-id').value.trim().toLowerCase();if(!uuid.test(id)){message('Введите UUID команды');return;}
 draftColors[id]=teamAccent(id,draftColors,auto,config?.backgroundColor);dirty=true;renderTeams();};
async function loadMatches() {
 try {
  const data=await readJson(`/api/v1/matches?page=${page}&size=20&sortBy=PLANNED_START_TIMESTAMP&sortDirection=DESC`);
  $('matches').replaceChildren(new Option('Выберите матч',''));
  $('links-matches').replaceChildren(new Option('Выберите матч',''));
  for(const match of data.content) {
   const label=`${match.teams.map(t=>t.teamName).join(' — ')} · ${match.status}`;
   $('matches').add(new Option(label,match.id));$('links-matches').add(new Option(label,match.id));
  }
  $('page').textContent=String(page+1);$('previous').disabled=page===0;$('next').disabled=page+1>=data.totalPages;
 }catch(error){if(disposed)return;$('status').textContent=`Список матчей: ${error.message}`;}
}
async function openMatch() {
 if(disposed||!obsPageCurrent())return;
 const selected=++openGeneration;
 const id=$('match-id').value.trim().toLowerCase();mountScoreboardAdmin(id);if(!uuid.test(id)){$('status').textContent='Введите UUID матча';return;}
 try {
  const match=await readJson(`/api/v1/matches/${id}`);if(selected!==openGeneration||disposed||!obsPageCurrent())return;teams=match.teams;
  $('preview').src=`/obs-editor/event-log.html?matchId=${id}`;$('status').textContent=`Редактор Event Log: ${id} · подключение…`;
  $('editor-context').textContent=`Редакторы открыты для матча ${id}. Настройки Event Log общие для сервиса.`;
  renderTeams();for(const team of teams)void imageAccent(team.photoUrl,images.signal).then(color=>{if(disposed||selected!==openGeneration||!obsPageCurrent())return;auto[team.teamId]=color;renderTeams();});

 }catch(error){if(selected===openGeneration)$('status').textContent=`Матч: ${error.message}`;}
}
$('matches').onchange=()=>{if($('matches').value){$('match-id').value=$('matches').value;void openMatch();}};
$('open-match').onclick=()=>void openMatch();
$('previous').onclick=()=>{page=Math.max(0,page-1);void loadMatches();};$('next').onclick=()=>{page++;void loadMatches();};
$('test').onclick=()=>{if(disposed||!obsPageCurrent())return;$('preview').contentWindow?.postMessage({type:'obs-test'},location.origin);};
addEventListener('message',event=>{if(!disposed&&obsPageCurrent()&&event.origin===location.origin&&event.source===$('preview').contentWindow&&event.data?.type==='obs-status')$('status').textContent=event.data.message;});
void loadMatches();
