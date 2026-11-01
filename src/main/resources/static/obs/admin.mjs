import {obsSession} from '../auth/obs-session.mjs';
import {mountScoreboardAdmin} from './scoreboard-admin.mjs';
import {readJson,watchConfig} from './client.mjs';
import {teamAccent,imageAccent} from './colors.mjs';
const $=id=>document.getElementById(id),uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
let config=null,draftColors={},teams=[],auto={},page=0,dirty=false;
const message=text=>$('save-status').textContent=text;
try {await obsSession.bootstrap('OBS_ADMIN');$('auth-status').textContent='Организатор: защищённая сессия проверена.';}
catch(error){$('auth-status').textContent=error.message;message('Изменения не отправляются. Войдите и откройте страницу заново.');throw error;}
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
const stop=watchConfig(value=>{
 config=value;$('save').disabled=false;
 if(dirty){message('Настройки изменились. Сохранение заменит общие настройки вашим вариантом.');return;}
 for(const name of ['maxRows','ttlSeconds','corner','backgroundColor','textColor'])$(name).value=value[name];
 document.querySelectorAll('[name=category]').forEach(input=>input.checked=value.categories.includes(input.value));
 draftColors={...value.teamColors};renderTeams();
},text=>message(text));
$('settings').addEventListener('input',()=>dirty=true);
$('settings').addEventListener('submit',async event=>{
 event.preventDefault();if(!config)return;
 const value={categories:[...document.querySelectorAll('[name=category]:checked')].map(input=>input.value),
 maxRows:Number($('maxRows').value),ttlSeconds:Number($('ttlSeconds').value),corner:$('corner').value,
 backgroundColor:$('backgroundColor').value,textColor:$('textColor').value,teamColors:draftColors};
 $('save').disabled=true;
 try {
  const response=await obsSession.protectedFetch('/api/v1/obs/config',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
  if(!response.ok){const error=await response.json();throw Error(error.detail??`HTTP ${response.status}`);}
  config=await response.json();dirty=false;message('Сохранено для всех OBS-источников.');
 }catch(error){message(error.message);}finally{$('save').disabled=false;}
});
$('add-team').onclick=()=>{const id=$('team-id').value.trim().toLowerCase();if(!uuid.test(id)){message('Введите UUID команды');return;}
 draftColors[id]=teamAccent(id,draftColors,auto,config?.backgroundColor);dirty=true;renderTeams();};
async function loadMatches() {
 try {
  const data=await readJson(`/api/v1/matches?page=${page}&size=20&sortBy=PLANNED_START_TIMESTAMP&sortDirection=DESC`);
  $('matches').replaceChildren(new Option('Выберите матч',''));
  for(const match of data.content)$('matches').add(new Option(`${match.teams.map(t=>t.teamName).join(' — ')} · ${match.status}`,match.id));
  $('page').textContent=String(page+1);$('previous').disabled=page===0;$('next').disabled=page+1>=data.totalPages;
 }catch(error){$('status').textContent=`Список матчей: ${error.message}`;}
}
let openGeneration=0;
async function openMatch() {
 const selected=++openGeneration;
 const id=$('match-id').value.trim().toLowerCase();mountScoreboardAdmin(id);if(!uuid.test(id)){$('status').textContent='Введите UUID матча';return;}
 try {
  const match=await readJson(`/api/v1/matches/${id}`);if(selected!==openGeneration)return;teams=match.teams;
  $('obs-url').value=new URL(`/obs-battle-log.html?matchId=${id}`,location.origin).href;
  $('preview').src=`/obs-battle-log.html?matchId=${id}&preview=1`;$('status').textContent='Подключение…';
  renderTeams();for(const team of teams)void imageAccent(team.photoUrl).then(color=>{auto[team.teamId]=color;renderTeams();});
 }catch(error){if(selected===openGeneration)$('status').textContent=`Матч: ${error.message}`;}
}
$('matches').onchange=()=>{if($('matches').value){$('match-id').value=$('matches').value;void openMatch();}};
$('open-match').onclick=()=>void openMatch();
$('previous').onclick=()=>{page=Math.max(0,page-1);void loadMatches();};$('next').onclick=()=>{page++;void loadMatches();};
$('copy').onclick=async()=>{if(!$('obs-url').value)return;try{await navigator.clipboard.writeText($('obs-url').value);$('status').textContent='Ссылка скопирована';}catch{$('obs-url').select();$('status').textContent='Скопируйте выделенную ссылку';}};
$('test').onclick=()=>{$('preview').contentWindow?.postMessage({type:'obs-test'},location.origin);};
addEventListener('message',event=>{if(event.origin===location.origin&&event.source===$('preview').contentWindow&&event.data?.type==='obs-status')$('status').textContent=event.data.message;});
addEventListener('pagehide',stop);void loadMatches();
