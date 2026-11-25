import {parseSource,watchProtectedSource} from './protected-source-client.mjs';
import {validateInfographics,rosterPage,RosterClock} from './infographics-model.mjs';
const root = document.getElementById('infographics'), status = document.getElementById('status');
const clock=new RosterClock();
let snapshot=null, disposed=false, viewKey='', stop=()=>{};
function node(tag,text='',className='') {
  const element=document.createElement(tag);element.textContent=text;element.className=className;return element;
}
function clear() {root.hidden=true;root.replaceChildren();viewKey='';}
function render() {
  const page=rosterPage(snapshot,clock.current());
  if (disposed || !page) {clear();return;}
  const key=JSON.stringify([page.matchId,page.presentationRevision,page.page,page.teams]);
  if (key===viewKey) return;
  viewKey=key;
  const pair=node('div','','team-pair');
  for (const team of page.teams) {
    const side=node('section','','team-side'), strip=node('header','','team-strip'), grid=node('div','','team-grid');
    strip.append(node('h3',team.name),node('p',team.total ? `${team.first+1}–${team.first+team.roster.length} из ${team.total}` : 'Нет игроков'));
    grid.setAttribute('aria-label',`${page.phase.kind === 'LINEUP' ? 'Состав поинта' : 'Ростер'} · ${team.name}`);
    for (const player of team.roster) {
      const card=node('article','','person'), photo=node('div',player.temporary ? '?' : player.name.split(/\s+/).filter(Boolean).map(n=>n[0]).slice(0,2).join(''),'photo');
      // Photos are optional. Initials avoid credentialed image loads or global roster dependencies.
      card.dataset.participantId=player.participantId;
      const name=node('p',player.name,'name');
      name.title=player.name;
      if (player.number!==null) photo.append(node('small',String(player.number),'number'));
      card.append(photo,name);grid.append(card);
    }
    side.append(strip,grid);pair.append(side);
  }
  root.replaceChildren(node('h2',page.phase.kind === 'LINEUP' ? 'Состав поинта' : 'Ростер команд'),pair);root.hidden=false;
}
function accept(value) {
  if (disposed) return;
  if (!clock.accept(value)) return;
  snapshot=value;render();
}
const tick=setInterval(render,100);
addEventListener('pagehide',()=>{disposed=true;stop();clearInterval(tick);snapshot=null;clear();},{once:true});
async function start() {
  try {
    const url=new URL(location.href);
    const preview=url.pathname==='/obs-preview/infographics.html'
      ? await (await import('./staff-preview.mjs')).openPreview(url,'infographics',()=>disposed) : null;
    if (disposed) {preview?.transport.dispose();return;}
    stop=watchProtectedSource(preview?.source??parseSource(url,'infographics'),accept,message=>{
      if (disposed) return;
      status.textContent=message;
      // A lost response cannot keep a roster visible after an unseen PULL or match switch.
      if (message) {snapshot=null;clear();}
    },{transport:preview?.transport,validate:validateInfographics,retryMessage:'Инфографика: переподключение…'});
  } catch {if (!disposed) {clear();status.textContent='Источник недоступен';}}
}
void start();
