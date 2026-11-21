const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
const kinds = ['scoreboard', 'event-log'], keys = '/api/v1/obs/source-keys';
const empty = (status = 'LOADING', message = '') => ({status, message, url: '', key: null, busy: false, retry: false});
function binding(kind, matchId) {
  return {kind, matchId, scope: `${matchId ? 'EXACT' : 'AUTO'}_${kind === 'scoreboard' ? 'SCOREBOARD' : 'EVENT_LOG'}`};
}
export function validateCurrent(data, context) {
  if (!data || !['ACTIVE', 'REISSUE_REQUIRED', 'NO_ACTIVE_LINK'].includes(data.status)) throw Error('invalid');
  if (data.status === 'NO_ACTIVE_LINK') {
    if (data.key !== null || data.url !== null) throw Error('invalid');
    return data;
  }
  const key = data.key;
  if (!key || !uuid.test(key.keyId) || key.scope !== context.scope || key.matchId !== context.matchId ||
      key.revokedAt != null || !Number.isFinite(Date.parse(key.createdAt))) throw Error('invalid');
  if (data.status === 'REISSUE_REQUIRED') {
    if (data.url !== null) throw Error('invalid');
  } else {
    const url = new URL(data.url);
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== `/obs-sources/${context.kind}.html` ||
        url.search !== (context.matchId ? `?matchId=${context.matchId}` : '?mode=auto') ||
        !/^#key=[A-Za-z0-9_-]{43}$/.test(url.hash)) throw Error('invalid');
  }
  return data;
}
// URLs live only in this page's state. Pending intent IDs contain no credentials.
export function createActiveLinks({session, onChange, randomId = () => crypto.randomUUID()}) {
  const captured = session.capture(), pending = new Map(), requests = new Set();
  let generation = 0, disposed = false;
  let state = {current: true, matchId: null, cards: Object.fromEntries(kinds.map(k => [k, empty()]))};
  const current = () => !disposed && session.isCurrent(captured);
  const emit = () => { if (!disposed) onChange(state); };
  const stop = () => {
    generation++;pending.clear();requests.forEach(request => request.abort());requests.clear();
    state = {...state, current: false, cards: Object.fromEntries(kinds.map(k => [k, empty('SESSION_CHANGED')]))};emit();
  };
  const unsubscribe = session.subscribe(() => {if (!current()) stop();});
  const valid = selected => {if (!current()) {if (!disposed) stop();return false;}return selected === generation;};
  const patch = (kind, value) => {state = {...state, cards: {...state.cards, [kind]: {...state.cards[kind], ...value}}};emit();};
  async function request(path, init, selected) {
    if (!valid(selected)) return;
    const abort = new AbortController();requests.add(abort);
    const timeout = setTimeout(() => abort.abort(), 8000);
    try {
      const response = await session.protectedFetch(path, {...init, signal: abort.signal});
      if (!valid(selected)) return;
      // Read status without trusting a problem body; ambiguous server failures retain intent below.
      if (!response.ok) return {error: response.status};
      const data = response.status === 204 ? null : await response.json();
      if (!valid(selected)) return;
      return {data};
    } finally {clearTimeout(timeout);requests.delete(abort);}
  }
  async function load(kind, selected) {
    const context = binding(kind, state.matchId), id = `${context.scope}:${context.matchId ?? ''}`;
    try {
      const result = await request(`${keys}/current${context.matchId ? '/'+context.matchId : ''}?scope=${context.scope}`, {method: 'GET'}, selected);
      if (!result || !valid(selected)) return;
      if (result.error) {
        patch(kind, {...empty(result.error === 503 ? 'UNAVAILABLE' : 'ERROR', `HTTP ${result.error}`), retry: pending.has(id)});return;
      }
      const data = validateCurrent(result.data, context);
      patch(kind, pending.has(id) ? {...empty('UNKNOWN', 'Выдача ещё не подтверждена. Повторите то же действие.'), retry: true} : {...empty(), ...data, url: data.url ?? ''});
    } catch {if (valid(selected)) patch(kind, {...empty('ERROR', 'Не удалось проверить текущую ссылку. Повторите чтение.'), retry: pending.has(id)});}
  }
  async function select(matchId) {
    if (!current()) {if (!disposed) stop();return;}
    if (matchId !== null && !uuid.test(matchId)) throw Error('Введите UUID матча');
    const selected = ++generation;
    requests.forEach(request => request.abort());
    state = {...state, matchId, cards: Object.fromEntries(kinds.map(k => [k, empty()]))};emit();
    await Promise.all(kinds.map(kind => load(kind, selected)));
  }
  async function issue(kind) {
    const selected = generation;
    if (!valid(selected) || !kinds.includes(kind) || state.cards[kind].busy) return;
    const context = binding(kind, state.matchId), id = `${context.scope}:${context.matchId ?? ''}`;
    const operationId = pending.get(id) ?? randomId();pending.set(id, operationId);
    patch(kind, {busy: true, message: ''});
    try {
      const result = await request(keys, {method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({operationId, scope: context.scope, ...(context.matchId ? {matchId: context.matchId} : {})})}, selected);
      if (!result || !valid(selected)) return;
      if (result.error) {
        if (result.error < 500) pending.delete(id);
        patch(kind, {...empty(result.error === 503 ? 'UNAVAILABLE' : result.error >= 500 ? 'UNKNOWN' : 'ERROR', `Выдача не подтверждена: HTTP ${result.error}`), retry: pending.has(id)});return;
      }
      const data = validateCurrent(result.data, context);
      if (data.status !== 'ACTIVE') throw Error('invalid');
      pending.delete(id);patch(kind, {...empty(), ...data});
    } catch {if (valid(selected)) patch(kind, {...empty('UNKNOWN', 'Результат выдачи неизвестен. Повтор использует то же действие.'), retry: true});}
  }
  function revokeIntent(kind) {
    const card = state.cards[kind];
    if (!current() || card?.busy || !card?.key || !['ACTIVE', 'REISSUE_REQUIRED'].includes(card.status)) return null;
    return Object.freeze({kind, keyId: card.key.keyId, generation, matchId: state.matchId});
  }
  async function revoke(intent) {
    if (!intent || !valid(intent.generation) || intent.matchId !== state.matchId || state.cards[intent.kind].busy) return;
    patch(intent.kind, {busy: true, message: ''});
    try {
      const result = await request(`${keys}/${intent.keyId}`, {method: 'DELETE'}, intent.generation);
      if (!result || !valid(intent.generation)) return;
      if (result.error) {patch(intent.kind, empty('ERROR', `Отзыв отклонён: HTTP ${result.error}`));return;}
      await load(intent.kind, intent.generation);
    } catch {if (valid(intent.generation)) patch(intent.kind, empty('UNKNOWN', 'Результат отзыва неизвестен. Проверьте текущую ссылку.'));}
  }
  return {
    get state() {return state;}, select, issue, revokeIntent, revoke,
    preview: kind => `/obs-preview/${kind}.html?${state.matchId ? 'matchId='+state.matchId : 'mode=auto'}`,
    dispose() {if (disposed) return;stop();disposed = true;unsubscribe?.();},
  };
}
