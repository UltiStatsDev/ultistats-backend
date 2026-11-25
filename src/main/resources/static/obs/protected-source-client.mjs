import { validateSnapshot } from './scoreboard-model.mjs';
const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const invalid = () => {
  throw Error('Некорректная ссылка или данные табло');
};
export function parseSource(url, kind = 'scoreboard') {
  const query = url.searchParams,
    fragment = new URLSearchParams(url.hash.slice(1));
  const auto = query.get('mode') === 'auto',
    matchId = query.get('matchId')?.toLowerCase(),
    key = fragment.get('key');
  if (
    url.protocol !== 'https:' ||
    url.username ||
    url.password ||
    !['scoreboard', 'event-log', 'infographics'].includes(kind) ||
    url.pathname !== `/obs-sources/${kind}.html` ||
    [...query].length !== 1 ||
    [...fragment].length !== 1 ||
    !fragment.has('key') ||
    !/^[A-Za-z0-9_-]{43}$/.test(key ?? '') ||
    (auto ? !query.has('mode') : !uuid.test(matchId ?? ''))
  )
    invalid();
  return { path: `/api/v1/obs/sources/${kind}/${auto ? 'auto' : matchId}`, matchId: auto ? null : matchId, key, origin: url.origin };
}
const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
export function validateScoreboard(value, matchId = value?.matchId, origin = globalThis.location?.origin ?? 'https://localhost') {
  validateSnapshot(value, matchId);
  if (
    typeof value.serverNow !== 'string' ||
    value.teams.some((team) => !Number.isSafeInteger(team.score)) ||
    (value.status === 'PLANNED' && value.startedAt !== null) ||
    (value.status !== 'FINISHED' && value.endedAt !== null)
  )
    invalid();
  if (!uuid.test(value.matchId) || new Set(value.teams.map((t) => t.teamId)).size !== 2 || value.teams.some((t) => !uuid.test(t.teamId)))
    invalid();
  const { shortNames, teamColors } = value.profile,
    ids = value.teams.map((t) => t.teamId);
  if (
    !record(shortNames) ||
    !record(teamColors) ||
    Object.entries(shortNames).some(([id, name]) => !ids.includes(id) || typeof name !== 'string' || name.length > 100) ||
    Object.entries(teamColors).some(([id, color]) => !ids.includes(id) || !/^#[0-9a-f]{6}$/i.test(color))
  )
    invalid();
  // Optional legacy/external logos degrade to initials; never make a sports snapshot unreadable.
  value = {
    ...value,
    teams: value.teams.map((team) => {
      let photoUrl = null;
      if (typeof team.photoUrl === 'string')
        try {
          const url = new URL(team.photoUrl, origin);
          if (url.origin === origin && url.protocol === 'https:' && !url.username && !url.password && !url.hash) photoUrl = url.href;
        } catch {}
      return { ...team, photoUrl };
    }),
  };
  return value;
}
// One request at a time; no public REST/SSE/worker fallback. Errors never interpolate response or token data.
export function watchProtectedSource(source, onSnapshot, onStatus = () => {}, options = {}) {
  const { fetch: request = globalThis.fetch, pollMs = 1000, retryMs = 1000, timeoutMs = 3000, validate = validateScoreboard, retryMessage = 'Табло: переподключение…' } = options;
  const transport = options.transport;
  let unsubscribe = () => {};
  let closed = false,
    timer = null,
    controller = null;
  const stop = () => {
    if (closed) return;
    closed = true;
    unsubscribe();
    transport?.dispose();
    clearTimeout(timer);
    controller?.abort();
  };
  const current = () => {
    if (closed) return false;
    if (transport && !transport.isCurrent()) {
      stop();
      onSnapshot(null);
      onStatus('Сессия изменилась. Откройте предпросмотр заново');
      return false;
    }
    return true;
  };
  if (transport) unsubscribe = transport.subscribe(current);
  async function read() {
    if (!current()) return;
    const active = new AbortController();
    controller = active;
    const timeout = setTimeout(() => active.abort(), timeoutMs);
    let delay = pollMs;
    try {
      const response = await (transport?.request ?? request)(source.path, {
        ...(transport ? {} : { headers: { Authorization: `Bearer ${source.key}` }, credentials: 'omit' }),
        cache: 'no-store',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
        signal: active.signal,
      });
      if (!current()) return;
      if ([401, 403, 404].includes(response.status)) {
        stop();
        onSnapshot(null);
        onStatus('Ссылка недоступна');
        return;
      }
      if (!response.ok) throw Error();
      const payload = await response.json();
      if (!current()) return;
      if (source.matchId === null && (!record(payload) || !Object.hasOwn(payload, 'snapshot'))) invalid();
      const snapshot = source.matchId === null ? payload.snapshot : payload;
      const value =
        snapshot === null && source.matchId === null
          ? null
          : validate(snapshot, source.matchId ?? snapshot?.matchId, source.origin);
      onSnapshot(value);
      onStatus(value ? '' : 'Нет матча для трансляции');
    } catch {
      if (current()) {
        delay = retryMs;
        onStatus(retryMessage);
      }
    } finally {
      clearTimeout(timeout);
      if (controller === active) controller = null;
      if (!closed) timer = setTimeout(read, Math.max(1, delay));
    }
  }
  void read();
  return stop;
}
