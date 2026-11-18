import { parseSource, watchProtectedSource } from './protected-source-client.mjs';
import { ClockAnchor } from './scoreboard-model.mjs';
import { renderScoreboard } from './scoreboard-renderer.mjs';
import { dominantColor } from './colors.mjs';
const root = document.getElementById('scoreboard'),
  stage = document.getElementById('stage'),
  status = document.getElementById('status');
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
  // The shared renderer remains unchanged. Only owned credentialless blob images enter this page.
  clockNode = renderScoreboard(root, { ...snapshot, teams: snapshot.teams.map((t) => ({ ...t, photoUrl: null })) }, snapshot.profile, auto);
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
  const key = JSON.stringify([value.teams, value.profile]);
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
try {
  stop = watchProtectedSource(parseSource(new URL(location.href)), accept, (message) => {
    if (!disposed) status.textContent = message;
  });
} catch {
  status.textContent = 'Некорректная ссылка табло';
}
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
