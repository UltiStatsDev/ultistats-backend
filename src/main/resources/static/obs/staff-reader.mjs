// Staff pages never use the ambient-cookie legacy SharedWorker transport.
async function read(session, captured, path, signal) {
  const current = () => session.isCurrent(captured);
  if (!current()) throw Error('Сессия изменилась');
  const response = await session.protectedFetch(path, {cache: 'no-store', signal});
  if (!current()) throw Error('Сессия изменилась');
  if (!response.ok) throw Object.assign(Error(`HTTP ${response.status}`), {terminal: [401,403].includes(response.status)});
  const value = await response.json();
  if (!current()) throw Error('Сессия изменилась');
  return value;
}
export function readStaffJson(session, path) {
  return read(session, session.capture(), path, AbortSignal.timeout(3000));
}
export function watchStaffJson(session, path, onValue, onStatus, onEnd, {pollMs = 1000, timeoutMs = 3000} = {}) {
  const captured = session.capture();
  let stopped = false, timer, controller, unsubscribe = () => {};
  function stop() {
    if (stopped) return;
    stopped = true; clearTimeout(timer); controller?.abort(); unsubscribe();
  }
  function end() {if (!stopped) {stop(); onEnd();}}
  unsubscribe = session.subscribe(() => {if (!session.isCurrent(captured)) end();});
  async function poll() {
    if (stopped) return;
    if (!session.isCurrent(captured)) {end(); return;}
    controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const value = await read(session, captured, path, controller.signal);
      if (!stopped && session.isCurrent(captured)) {onValue(value); onStatus('Синхронизировано');}
    } catch (error) {
      if (!session.isCurrent(captured) || error.terminal) end();
      else if (!stopped) onStatus('Нет связи. Повтор…');
    } finally {
      clearTimeout(timeout);
      if (!stopped) timer = setTimeout(poll, pollMs);
    }
  }
  void poll();
  return stop;
}
