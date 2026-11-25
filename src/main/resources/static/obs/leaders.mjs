import {watchStaffJson} from './staff-reader.mjs';
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
export function leaderPath(mode, matchId) {
  if (mode !== 'auto' && (mode !== 'exact' || !uuid.test(matchId))) throw Error('Укажите полный UUID матча');
  return `/api/v1/obs/infographics/leaders/${mode === 'auto' ? 'auto' : matchId.toLowerCase()}`;
}
export function watchLeaders(session, path, render, status) {
  let stopped = false;
  const clear = () => render(null);
  const stop = watchStaffJson(session, path, value => {
    const snapshot = path.endsWith('/auto') ? value.snapshot : value;
    if (snapshot && !path.endsWith('/auto') && !path.endsWith('/' + snapshot.matchId)) {
      clear(); status('Контекст матча изменился'); return;
    }
    render(snapshot);
  }, message => {if (message !== 'Синхронизировано') clear(); status(message);}, () => {clear(); status('Сессия завершена. Откройте страницу заново');});
  return () => {if (!stopped) {stopped = true; stop(); clear();}};
}
async function open() {
  const form = document.querySelector('form'), output = document.querySelector('#status'), content = document.querySelector('#leaders');
  const mode = form.elements.mode, match = form.elements.matchId;
  const status = message => {output.textContent = message;};
  let stop = () => {}, disposed = false, unsubscribe = () => {};
  function render(snapshot) {
    content.replaceChildren();
    if (!snapshot) {content.textContent = 'Нет данных для выбранного контекста'; return;}
    const heading = document.createElement('p');
    heading.textContent = `Матч ${snapshot.matchId} · ${snapshot.status}`; content.append(heading);
    for (const team of snapshot.teams) {
      const section = document.createElement('section'), title = document.createElement('h2');
      title.textContent = team.name; section.append(title);
      if (team.state === 'UNAVAILABLE') {
        const text = document.createElement('p'); text.textContent = 'Статистика недоступна: неконсистентные счётчики'; section.append(text);
      } else for (const [field, label] of [['attack', 'Атака'], ['defense', 'Защита']]) {
        const title = document.createElement('h3'); title.textContent = label; section.append(title);
        const list = document.createElement('ul');
        for (const leader of team[field]) {
          const item = document.createElement('li'); item.dataset.participantId = leader.participantId;
          const rating = `${Math.floor(leader.ratingTenths / 10)}.${leader.ratingTenths % 10}`;
          item.textContent = `${leader.name}${leader.number == null ? '' : ' · №' + leader.number} — ${rating}`; list.append(item);
        }
        if (!list.children.length) {const item = document.createElement('li'); item.textContent = 'Нет результативных действий'; list.append(item);}
        section.append(list);
      }
      content.append(section);
    }
  }
  let session;
  const end = () => {disposed = true; stop(); unsubscribe(); session?.dispose(); form.querySelector('fieldset').disabled = true;};
  addEventListener('pagehide', end, {once: true});
  try {
    const {createStaffSession} = await import('/auth/staff-session.mjs');
    if (disposed) return;
    session = createStaffSession();
    await session.bootstrap('OBS_ADMIN');
    if (disposed) {session.dispose(); return;}
    const captured = session.capture();
    unsubscribe = session.subscribe(() => {if (!session.isCurrent(captured)) {end(); status('Сессия изменилась. Откройте страницу заново');}});
    form.querySelector('fieldset').disabled = false;
    mode.addEventListener('change', () => {stop(); match.disabled = mode.value === 'auto'; status('Контекст изменён. Нажмите «Обновить»');});
    match.addEventListener('input', () => {stop(); status('Контекст изменён. Нажмите «Обновить»');});
    form.addEventListener('submit', event => {
      event.preventDefault(); stop();
      if (disposed || !session.isCurrent(captured)) return;
      try {stop = watchLeaders(session, leaderPath(mode.value, match.value), render, status);} catch (error) {status(error.message);}
    });
    form.requestSubmit();
  } catch (error) {end(); status(error.message);}
}
if (typeof document !== 'undefined') void open();
