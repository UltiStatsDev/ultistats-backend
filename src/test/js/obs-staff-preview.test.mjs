import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { parsePreview, capturePreviewTransport } from '../../main/resources/static/obs/staff-preview.mjs';
import { watchProtectedSource } from '../../main/resources/static/obs/protected-source-client.mjs';
const id = 'abcdefab-cdef-abcd-efab-cdefabcdefab';
const url = (query = `matchId=${id}`) => new URL(`https://localhost/obs-preview/scoreboard.html?${query}`);
const pause = (ms = 5) => new Promise((resolve) => setTimeout(resolve, ms));
function session() {
  let current = true, listener, disposed = false;
  const capture = {};
  return {
    capture: () => capture, isCurrent: (value) => current && value === capture,
    subscribe: (value) => { listener = value; return () => { listener = null; }; },
    invalidate: () => { current = false; listener?.(); },
    protectedFetch: async (path, init) => { assert.ok(path.startsWith('/api/v1/obs/previews/')); assert.equal(init.headers, undefined); return { ok: true }; },
    dispose: () => { disposed = true; },
    get disposed() { return disposed; },
    get listening() { return !!listener; },
  };
}
test('preview URL is a distinct HTTPS keyless exact or AUTO contract', () => {
  assert.equal(parsePreview(url()).path, `/api/v1/obs/previews/scoreboard/${id}`);
  assert.equal(parsePreview(url('mode=auto')).matchId, null);
  assert.equal(parsePreview(new URL('https://localhost/obs-preview/event-log.html?mode=auto'), 'event-log').path, '/api/v1/obs/previews/event-log/auto');
  for (const value of [url('mode=AUTO'), url(`matchId=${id}&mode=auto`), url(`matchId=${id}&matchId=${id}`), url('mode=auto&key=hidden'), new URL(`${url()}#key=hidden`), new URL(url().href.replace('https:', 'http:')), new URL(url().href.replace('obs-preview', 'obs-sources'))]) assert.throws(() => parsePreview(value));
});
test('captured transport uses helper and prevents requests after capture replacement', async () => {
  const helper = session(), source = parsePreview(url()), transport = capturePreviewTransport(helper, source);
  await transport.request(source.path, {});
  await assert.rejects(transport.request('/api/v1/obs/sources/scoreboard/auto', {}));
  helper.invalidate();
  await assert.rejects(transport.request(source.path, {}));
  transport.dispose();
  assert.equal(helper.disposed, true);
});
test('capture loss during pending JSON clears, discards late data and ends polling', async () => {
  const helper = session(), source = parsePreview(url('mode=auto'));
  let deliver, calls = 0;
  helper.protectedFetch = async () => { calls++; return { ok: true, status: 200, json: () => new Promise((r) => { deliver = r; }) }; };
  const transport = capturePreviewTransport(helper, source), seen = [];
  const stop = watchProtectedSource(source, (v) => seen.push(v), () => {}, { transport, pollMs: 1, validate: (v) => v });
  await pause();
  helper.invalidate();
  deliver({ snapshot: { matchId: id } });
  await pause(15);
  assert.deepEqual(seen, [null]);
  assert.equal(calls, 1);
  assert.equal(helper.listening, false);
  stop();
});
test('stop while fetch pending closes session listener and discards result', async () => {
  const helper = session(), source = parsePreview(url('mode=auto'));
  let deliver;
  helper.protectedFetch = () => new Promise((r) => { deliver = r; });
  const seen = [], stop = watchProtectedSource(source, (v) => seen.push(v), () => {}, { transport: capturePreviewTransport(helper, source) });
  await pause(); stop(); deliver({ ok: true, json: async () => ({ snapshot: null }) }); await pause();
  assert.deepEqual(seen, []); assert.equal(helper.disposed, true); assert.equal(helper.listening, false);
});
test('viewer entry modules never statically depend on private staff module', async () => {
  for (const file of ['protected-scoreboard', 'protected-event-log']) {
    const source = await readFile(new URL(`../../main/resources/static/obs/${file}.mjs`, import.meta.url), 'utf8');
    assert.doesNotMatch(source, /import\s+[^;\n]*\sfrom\s+['"][^'"]*staff/);
  }
});
test('late bootstrap rejection after pagehide never mutates either entry', async () => {
  const vm = await import('node:vm');
  for (const [kind, file] of [['scoreboard', 'protected-scoreboard'], ['event-log', 'protected-event-log']]) {
    let reject, mutations = 0;
    const listeners = {};
    const element = { style: {}, dataset: {}, replaceChildren() {}, querySelectorAll: () => [],
      set textContent(value) { mutations++; }, get textContent() { return ''; } };
    const source = (await readFile(new URL(`../../main/resources/static/obs/${file}.mjs`, import.meta.url), 'utf8'))
      .replace(/^import .*;\n/gm, '').replace("await (await import('./staff-preview.mjs')).openPreview(url,", 'await openPreview(url,');
    const context = vm.createContext({ document: { getElementById: () => element }, location: { href: `https://localhost/obs-preview/${kind}.html?mode=auto` },
      URL, Map, Object, performance, innerWidth: 1000, innerHeight: 160,
      addEventListener: (name, fn) => { listeners[name] = fn; }, removeEventListener() {}, setInterval: () => 1, clearInterval() {},
      ClockAnchor: class {}, Feed: class {}, Renderer: class { dispose() {} }, openPreview: () => new Promise((_, r) => { reject = r; }),
    });
    const result = vm.runInContext(`(async () => {${source}\n})()`, context);
    listeners.pagehide(); const before = mutations;
    reject(Error('late bootstrap')); await result;
    assert.equal(mutations, before, kind);
  }
});
test('pagehide during private helper import prevents late bootstrap entirely', async () => {
  const vm = await import('node:vm');
  let resolve, hidden = false, calls = 0;
  const module = new Promise((r) => { resolve = r; });
  const source = (await readFile(new URL('../../main/resources/static/obs/staff-preview.mjs', import.meta.url), 'utf8'))
    .replace(/export /g, '').replace("await import('/auth/staff-session.mjs')", 'await helperModule');
  const context = vm.createContext({ URL, helperModule: module, addEventListener() {}, removeEventListener() {} });
  vm.runInContext(source, context);
  const flight = context.openPreview(url(), 'scoreboard', () => hidden);
  hidden = true;
  resolve({ createStaffSession: () => { calls++; throw Error('must not start'); } });
  await assert.rejects(flight);
  assert.equal(calls, 0);
});

test('private editor entries are explicit-match only and cannot become AUTO or viewer entries', () => {
  for (const kind of ['scoreboard','event-log']) {
    const editor = new URL(`https://localhost/obs-editor/${kind}.html?matchId=${id}`);
    assert.equal(parsePreview(editor,kind).path,`/api/v1/obs/previews/${kind}/${id}`);
    for(const query of ['mode=auto',`matchId=${id}&preview=1`,`matchId=${id}&matchId=${id}`])
      assert.throws(()=>parsePreview(new URL(`https://localhost/obs-editor/${kind}.html?${query}`),kind));
  }
});
