import {parseSource,watchProtectedSource} from './protected-source-client.mjs';
import {validateInfographics,infographicsPage,RosterClock} from './infographics-model.mjs';
import {renderPostmatch} from './infographics-postmatch.mjs';
const root = document.getElementById('infographics'), status = document.getElementById('status');
const clock=new RosterClock();
let statsOnly=false, snapshot=null, disposed=false, viewKey='', stop=()=>{};
const photos = new Map();
const photoTypes = new Set(['image/jpeg','image/png','image/webp']);
const maxPhotoBytes = 10 * 1024 * 1024;
const maxInactiveBytes = 32 * 1024 * 1024;
const maxInactivePhotos = 48;
let visiblePhotos = new Set();

function forgetPhoto(source) {
  const photo = photos.get(source);
  photos.delete(source);
  photo.controller.abort();
  if (photo.url) URL.revokeObjectURL(photo.url);
  photo.url = null;
  photo.bytes = 0;
}

// A page can show up to ten valid uploads. Preserve those while bounding earlier pages separately.
function trimInactivePhotos() {
  let count = 0, bytes = 0;
  for (const [source, photo] of [...photos].reverse()) {
    if (visiblePhotos.has(source)) continue;
    if (count >= maxInactivePhotos || bytes + photo.bytes > maxInactiveBytes) {
      forgetPhoto(source);
    } else {
      count++;
      bytes += photo.bytes;
    }
  }
}

function keepPhotos(value) {
  const urls = new Set();
  if (!statsOnly && value) {
    for (const team of value.teams) for (const player of team.roster) urls.add(player.photoUrl);
  }
  for (const source of photos.keys()) if (!urls.has(source)) forgetPhoto(source);
}

async function fetchPhoto(source, photo) {
  const signal = photo.controller.signal;
  const timeout = setTimeout(() => photo.controller.abort(), 3000);
  try {
    const response = await fetch(source, {
      credentials:'omit', cache:'no-store', redirect:'error', referrerPolicy:'no-referrer', signal,
    });
    if (!response.ok || !photoTypes.has(response.headers.get('Content-Type')) ||
        Number(response.headers.get('Content-Length')) > maxPhotoBytes) {
      await response.body?.cancel();
      return null;
    }
    const blob = await response.blob();
    if (signal.aborted || !blob.size || blob.size > maxPhotoBytes) return null;
    photo.url = URL.createObjectURL(blob);
    photo.bytes = blob.size;
    trimInactivePhotos();
    if (signal.aborted) return null;
    const image = new Image();
    image.src = photo.url;
    await Promise.race([
      image.decode(),
      new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason), {once:true})),
    ]);
    if (!signal.aborted) return photo.url;
  } catch {
    // Keep a negative result for this binding to avoid repeated requests for broken media.
  } finally {
    clearTimeout(timeout);
  }
  if (photo.url) {
    URL.revokeObjectURL(photo.url);
    photo.url = null;
    photo.bytes = 0;
  }
  return null;
}

function photoFor(source) {
  let photo = photos.get(source);
  if (photo) {
    photos.delete(source);
  } else {
    photo = {controller:new AbortController(), url:null, bytes:0};
    photo.ready = fetchPhoto(source, photo);
  }
  photos.set(source, photo);
  return photo;
}

function node(tag,text='',className='') {
  const element=document.createElement(tag);element.textContent=text;element.className=className;return element;
}
function clear() {
  if (!viewKey) return;
  root.hidden=true;root.replaceChildren();viewKey='';
  visiblePhotos.clear();
  trimInactivePhotos();
}
function reset() {snapshot=null;keepPhotos(null);clear();}
function playerCard(player) {
  const initials = player.temporary ? '?' : player.name.split(/\s+/).filter(Boolean).map(n=>n[0]).slice(0,2).join('');
  const card = node('article','','person'), photo = node('div','','photo');
  const fallback = node('span',initials);
  photo.append(fallback);
  card.dataset.participantId = player.participantId;
  const name = node('p',player.name,'name');
  name.title = player.name;
  if (player.photoUrl) {
    const entry = photoFor(player.photoUrl);
    void entry.ready.then(url => {
      if (!url || entry.url !== url || !photo.isConnected) return;
      const image = document.createElement('img');
      image.alt = '';
      image.src = url;
      photo.prepend(image);
      fallback.hidden = true;
    });
  }
  if (player.number != null) photo.append(node('small',String(player.number),'number'));
  card.append(photo,name);
  return card;
}
function renderLeaders(page) {
  const attack = page.category === 'attack';
  const pair = node('div','','team-pair');
  for (const team of page.teams) {
    const side = node('section','','team-side');
    side.append(node('h3',team.name));
    const player = team.leader;
    if (!player) {
      side.append(node('p',team.state === 'UNAVAILABLE' ? 'Статистика недоступна' : 'Нет результативных действий','no-leader'));
    } else {
      const stats = player.components, detail = node('div','','leader-detail');
      const rows = attack
        ? [['Точные пасы без ассистов',stats.ordinaryAccuratePasses],['Ассисты',stats.assists],['Голы',stats.ratedGoals]]
        : [['Блоки',stats.blocks+stats.blocksAsMarker+stats.blocksAsFieldPlayer],['Перехваты',stats.interceptions],['Кэллаханы',stats.callahans]];
      detail.append(playerCard(player));
      for (const [label,value] of [...rows,['Итог',(player.ratingTenths/10).toFixed(1)]]) {
        const row = node('p','','leader-stat');
        row.append(node('span',label),node('strong',String(value)));
        detail.append(row);
      }
      side.append(detail);
    }
    pair.append(side);
  }
  root.dataset.graphicKind = 'leaders';
  root.dataset.category = page.category;
  root.replaceChildren(node('h2',attack ? 'Лидеры атаки' : 'Лидеры защиты'),pair);
  root.hidden = false;
}
function render() {
  const page=statsOnly
    ? snapshot?.postmatch && {...snapshot,phase:{kind:'POSTMATCH'}}
    : infographicsPage(snapshot,clock.current());
  if (disposed || !page) {clear();return;}
  const key = page.generation
    ? JSON.stringify([page.generation,page.category,page.page,page.teams.map(team=>team.leader?.photoUrl)])
    : JSON.stringify([page.matchId,page.presentationRevision,page.phase.kind,page.statsPresentation?.generation,page.playerGeneration,page.page,page.teams]);
  if (key===viewKey) return;
  viewKey=key;
  const visiblePlayers = page.generation ? page.teams.map(team => team.leader)
    : page.phase.kind === 'POSTMATCH' ? [] : page.teams.flatMap(team => team.roster);
  visiblePhotos = new Set(visiblePlayers.map(player => player?.photoUrl).filter(Boolean));
  trimInactivePhotos();
  if (page.generation) {renderLeaders(page);return;}
  delete root.dataset.category;
  root.dataset.graphicKind = page.phase.kind.toLowerCase();
  if (page.phase.kind === 'POSTMATCH') {
    root.replaceChildren(renderPostmatch(node,page.teams,page.postmatch));
    root.hidden = false;
    return;
  }
  const pair=node('div','','team-pair');
  for (const team of page.teams) {
    const side=node('section','','team-side'), strip=node('header','','team-strip'), grid=node('div','','team-grid');
    strip.append(node('h3',team.name),node('p',team.total ? `${team.first+1}–${team.first+team.roster.length} из ${team.total}` : 'Нет игроков'));
    grid.setAttribute('aria-label',`${page.phase.kind === 'LINEUP' ? 'Состав поинта' : 'Ростер'} · ${team.name}`);
    for (const player of team.roster) grid.append(playerCard(player));
    side.append(strip,grid);pair.append(side);
  }
  root.replaceChildren(node('h2',page.phase.kind === 'LINEUP' ? 'Состав поинта' : 'Ростер команд'),pair);root.hidden=false;
}
function accept(value) {
  if (disposed) return;
  if (!clock.accept(value)) return;
  if (snapshot?.matchId !== value?.matchId) keepPhotos(null);
  keepPhotos(value);
  snapshot=value;render();
}
const tick=setInterval(render,100);
addEventListener('pagehide',()=>{disposed=true;stop();clearInterval(tick);reset();},{once:true});
async function start() {
  try {
    const url=new URL(location.href);
    const preview=url.pathname==='/obs-preview/infographics.html'
      ? await (await import('./staff-preview.mjs')).openPreview(url,'infographics',()=>disposed) : null;
    if (disposed) {preview?.transport.dispose();return;}
    statsOnly=preview?.source.statsOnly === true;
    stop=watchProtectedSource(preview?.source??parseSource(url,'infographics'),accept,message=>{
      if (disposed) return;
      status.textContent=message || (statsOnly && !snapshot?.postmatch ? 'Итоги появятся после завершения матча' : '');
      // A lost response cannot keep a roster visible after an unseen PULL or match switch.
      if (message) reset();
    },{transport:preview?.transport,validate:validateInfographics,retryMessage:'Инфографика: переподключение…'});
  } catch {if (!disposed) {clear();status.textContent='Источник недоступен';}}
}
void start();
