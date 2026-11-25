const uuid = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i;
export function parsePreview(url, kind = 'scoreboard') {
  const query = url.searchParams, auto = query.get('mode') === 'auto', matchId = query.get('matchId')?.toLowerCase();
  const editor = url.pathname === `/obs-editor/${kind}.html`;
  if (url.protocol !== 'https:' || url.username || url.password || url.hash ||
      !['scoreboard', 'event-log', 'infographics'].includes(kind) || (!editor && url.pathname !== `/obs-preview/${kind}.html`) || (editor && auto) ||
      [...query].length !== 1 || (auto ? !query.has('mode') : !uuid.test(matchId ?? '')))
    throw Error('Некорректная ссылка предпросмотра');
  return { path: `/api/v1/obs/previews/${kind}/${auto ? 'auto' : matchId}`, matchId: auto ? null : matchId, origin: url.origin };
}
// A page capture can only end; this adapter never adopts a replacement principal/session.
export function capturePreviewTransport(session, source) {
  const captured = session.capture();
  const isCurrent = () => session.isCurrent(captured);
  return {
    isCurrent,
    subscribe: (listener) => session.subscribe(listener),
    async request(path, init) {
      if (path !== source.path || !isCurrent()) throw Error('Сессия изменилась');
      const result = await session.protectedFetch(path, init);
      if (!isCurrent()) throw Error('Сессия изменилась');
      return result;
    },
    dispose: () => session.dispose(),
  };
}
export async function openPreview(url, kind, isDisposed = () => false) {
  const source = parsePreview(url, kind);
  const { createStaffSession } = await import('/auth/staff-session.mjs');
  if (isDisposed()) throw Error('Предпросмотр закрыт');
  const session = createStaffSession();
  const hide = () => session.dispose();
  addEventListener('pagehide', hide, { once: true });
  try {
    await session.bootstrap('OBS_ADMIN');
    const transport = capturePreviewTransport(session, source);
    return { source, transport: { ...transport, dispose() {
      removeEventListener('pagehide', hide);
      transport.dispose();
    } } };
  } catch (error) {
    removeEventListener('pagehide', hide);
    session.dispose();
    throw error;
  }
}
