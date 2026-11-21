import { parseSource, watchProtectedSource } from './protected-source-client.mjs';
import { ClockAnchor } from './scoreboard-model.mjs';
import { renderScoreboard } from './scoreboard-renderer.mjs';
import { dominantColor } from './colors.mjs';
const root = document.getElementById('scoreboard'),
  stage = document.getElementById('stage'),
  status = document.getElementById('status');
let draft = null, editorCurrent = () => false;
const editor = location.pathname === '/obs-editor/scoreboard.html';
let snapshot = null,
  clock = new ClockAnchor(),
  clockNode = null,
  generation = 0,
  disposed = false,
  viewKey = null,
  stop = () => {};
const auto = {},
  photos = new Map();
function scale() {
  stage.style.transform = `scale(${Math.min(innerWidth / 1000, innerHeight / 160)})`;
}
function reset() {
  draft = null;
  generation++;
  clock = new ClockAnchor();
  clockNode = null;
  viewKey = null;
  for (const photo of photos.values()) {
    photo.controller.abort();
    if (photo.url) URL.revokeObjectURL(photo.url);
  }
  photos.clear();
  for (const id of Object.keys(auto)) delete auto[id];
  root.replaceChildren();
  root.hidden = true;
}
function render() {
  if (!snapshot || disposed) return;
  // Only owned credentialless blob images enter this page.
  clockNode = renderScoreboard(root, { ...snapshot, teams: snapshot.teams.map((t) => ({ ...t, photoUrl: null })) }, draft ?? snapshot.profile, auto);
  clockNode.textContent = clock.label();
  for (const team of snapshot.teams) {
    const photo = photos.get(team.teamId);
    if (!photo?.ready) continue;
    const logo = [...root.querySelectorAll('.scoreboard-team')]
      .find((n) => n.dataset.teamId === team.teamId)
      ?.querySelector('.scoreboard-logo');
    if (!logo) continue;
    const img = document.createElement('img');
    img.alt = '';
    img.src = photo.url;
    logo.querySelector('span').hidden = true;
    logo.append(img);
  }
}
async function loadPhoto(team, epoch) {
  const state = { source: team.photoUrl, controller: new AbortController(), url: null, ready: false };
  photos.set(team.teamId, state);
  const valid = () => !disposed && generation === epoch && photos.get(team.teamId) === state;
  const timer = setTimeout(() => state.controller.abort(), 3000);
  try {
    const response = await fetch(team.photoUrl, {
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      signal: state.controller.signal,
    });
    if (!response.ok || !valid()) return;
    const blob = await response.blob();
    if (!valid() || state.controller.signal.aborted) return;
    const url = URL.createObjectURL(blob);
    state.url = url;
    const image = new Image();
    image.src = url;
    await Promise.race([
      image.decode(),
      new Promise((_, reject) => state.controller.signal.addEventListener('abort', () => reject(Error()), { once: true })),
    ]);
    if (!valid()) return;
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    context.drawImage(image, 0, 0, 64, 64);
    auto[team.teamId] = dominantColor(context.getImageData(0, 0, 64, 64).data);
    state.ready = true;
    render();
  } catch {
    if (state.url) URL.revokeObjectURL(state.url);
    state.url = null;
  } finally {
    clearTimeout(timer);
  }
}
function accept(value) {
  if (disposed) return;
  if (value?.matchId !== snapshot?.matchId || value === null) reset();
  snapshot = value;
  if (!value) return;
  clock.accept(value);
  const key = JSON.stringify([value.teams, value.profile, value.status, value.activePauses]);
  if (key !== viewKey) {
    viewKey = key;
    for (const team of value.teams) {
      const previous = photos.get(team.teamId);
      if (previous?.source === team.photoUrl) continue;
      previous?.controller.abort();
      if (previous?.url) URL.revokeObjectURL(previous.url);
      photos.delete(team.teamId);
      delete auto[team.teamId];
      if (team.photoUrl) void loadPhoto(team, generation);
    }
    render();
  }
}
addEventListener('resize', scale);
scale();
const tick = setInterval(() => {
  if (clockNode && !disposed) clockNode.textContent = clock.label();
}, 1000);
addEventListener(
  'pagehide',
  () => {
    disposed = true;
    stop();
    clearInterval(tick);
    removeEventListener('resize', scale);
    reset();
  },
  { once: true },
);

async function start() {
try {
  const url = new URL(location.href);
  const preview = (url.pathname === '/obs-preview/scoreboard.html' || editor)
    ? await (await import('./staff-preview.mjs')).openPreview(url, 'scoreboard', () => disposed) : null;
  if (disposed) preview?.transport.dispose();
  else {
  if (editor) editorCurrent = preview.transport.isCurrent;
  stop = watchProtectedSource(preview?.source ?? parseSource(url), accept, (message) => {
    if (!disposed) {status.textContent = message; if (editor && editorCurrent()) parent.postMessage({type:'scoreboard-status',message:message || 'Синхронизировано'},location.origin);}
  }, { transport: preview?.transport });
}
} catch {
  if (disposed) return;
  status.textContent = 'Некорректная ссылка табло';
}
}
void start();

if (editor) addEventListener('message', event => {
  if (disposed || !snapshot || !editorCurrent() || event.origin !== location.origin || event.source !== parent || parent === window || event.data?.type !== 'scoreboard-draft') return;
  const value = event.data.profile, ids = snapshot.teams.map(t => t.teamId);
  if (!value || typeof value.swapped !== 'boolean' || !value.shortNames || !value.teamColors ||
      Object.entries(value.shortNames).some(([id,name]) => !ids.includes(id) || typeof name !== 'string' || name.length > 100) ||
      Object.entries(value.teamColors).some(([id,color]) => !ids.includes(id) || !/^#[0-9a-f]{6}$/i.test(color))) return;
  draft = structuredClone(value); render();
});
