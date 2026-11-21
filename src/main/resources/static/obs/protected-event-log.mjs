import { parseSource, watchProtectedSource } from "./protected-source-client.mjs";
import { validateEventLog, eventLogMatch } from "./protected-event-log-model.mjs";
import { Feed } from "./feed.mjs";
import { dominantColor } from "./colors.mjs";
import { Renderer } from "./renderer.mjs";
const root = document.getElementById("feed"),
  status = document.getElementById("status");
const photos = new Map(),
  autoColors = {};
let generation = 0;
let feed = new Feed(undefined, () => performance.now(), { protected: true }),
  renderer = new Renderer(root),
  match = null,
  disposed = false,
  stop = () => {};
function reset() {
  generation++;
  for (const photo of photos.values()) photo.controller.abort();
  photos.clear();
  for (const id of Object.keys(autoColors)) delete autoColors[id];
  renderer.dispose();
  feed = new Feed(undefined, () => performance.now(), { protected: true });
  renderer = new Renderer(root);
  match = null;
  delete root.dataset.corner;
}
function render() {
  if (match && !disposed) renderer.render(feed.rows(), match, feed.config, autoColors);
}
function photoSource(value) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value, location.origin);
    if (url.origin === location.origin && url.protocol === "https:" && !url.username && !url.password && !url.hash)
      return url.href;
  } catch {}
  return null;
}
async function loadColor(teamId, source, epoch) {
  const state = { source, controller: new AbortController() };
  photos.set(teamId, state);
  const valid = () => !disposed && generation === epoch && photos.get(teamId) === state && !state.controller.signal.aborted;
  const timeout = setTimeout(() => state.controller.abort(), 3000);
  let blobUrl;
  try {
    const response = await fetch(source, {
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      referrerPolicy: "no-referrer",
      signal: state.controller.signal,
    });
    if (!response.ok || !valid()) return;
    const blob = await response.blob();
    if (!valid()) return;
    blobUrl = URL.createObjectURL(blob);
    const image = new Image();
    image.src = blobUrl;
    await Promise.race([
      image.decode(),
      new Promise((_, reject) => state.controller.signal.addEventListener("abort", () => reject(Error()), { once: true })),
    ]);
    if (!valid()) return;
    const canvas = document.createElement("canvas");
    canvas.width = 64;
    canvas.height = 64;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    context.drawImage(image, 0, 0, 64, 64);
    autoColors[teamId] = dominantColor(context.getImageData(0, 0, 64, 64).data);
    render();
  } catch {
  } finally {
    clearTimeout(timeout);
    if (blobUrl) URL.revokeObjectURL(blobUrl);
  }
}
function accept(snapshot) {
  if (disposed) return;
  if (!snapshot || snapshot.matchId !== match?.matchId) reset();
  if (!snapshot) return;
  if (snapshot.logRevision < feed.revision) return;
  match = { ...eventLogMatch(snapshot), matchId: snapshot.matchId };
  for (const team of snapshot.teams) {
    const source = photoSource(team.photoUrl),
      previous = photos.get(team.teamId);
    if (previous?.source === source) continue;
    previous?.controller.abort();
    photos.delete(team.teamId);
    delete autoColors[team.teamId];
    if (source) void loadColor(team.teamId, source, generation);
  }
  feed.configure(snapshot.config);
  // Work on copies only; original event identities and canonical payloads remain intact.
  feed.reconcile({
    ...snapshot,
    events: snapshot.events.map(displayEvent),
    retractions: snapshot.retractions.map((r) => ({ ...r, event: displayEvent(r.event) })),
  });
  render();
}
function displayEvent(event) {
  return {
      ...event,
      fromParticipantId: event.fromSlotId ?? event.fromParticipantId,
      toParticipantId: event.toSlotId ?? event.toParticipantId,
  };
}
const tick = setInterval(render, 100);
addEventListener(
  "pagehide",
  () => {
    disposed = true;
    status.textContent = "";
    stop();
    clearInterval(tick);
    reset();
  },
  { once: true },
);

async function start() {
try {
  const url = new URL(location.href);
  const preview = url.pathname === '/obs-preview/event-log.html'
    ? await (await import('./staff-preview.mjs')).openPreview(url, 'event-log', () => disposed) : null;
  if (disposed) preview?.transport.dispose();
  else stop = watchProtectedSource(
    preview?.source ?? parseSource(url, "event-log"),
    accept,
    (message) => {
      if (!disposed) status.textContent = message;
    },
    { transport: preview?.transport, validate: validateEventLog, retryMessage: "Журнал: переподключение…" },
  );
} catch {
  if (disposed) return;
  reset();
  status.textContent = "Некорректная ссылка журнала";
}
}
void start();
