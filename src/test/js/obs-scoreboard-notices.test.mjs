import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { renderScoreboard } from '../../main/resources/static/obs/scoreboard-renderer.mjs';
import { ClockAnchor, defaultProfile } from '../../main/resources/static/obs/scoreboard-model.mjs';

class Element {
  constructor() { this.children = []; this.dataset = {}; this.style = {}; }
  append(...nodes) { this.children.push(...nodes); }
  replaceChildren(...nodes) { this.children = nodes; }
  setAttribute(name, value) { this[name] = value; }
  querySelectorAll(selector) {
    return this.children.flatMap(n => [...(n.className?.split(' ').includes(selector.slice(1)) ? [n] : []), ...n.querySelectorAll(selector)]);
  }
}
const sample = (types = [], status = 'IN_PROGRESS') => ({
  matchId: 'a', status, startedAt: '2026-01-01T00:00:00Z', endedAt: '2026-01-01T00:01:30Z',
  serverNow: '2026-01-01T00:02:00Z', profile: defaultProfile(),
  teams: ['a', 'b'].map(teamId => ({ teamId, teamName: teamId, score: 2, photoUrl: null })),
  activePauses: types.map((type, id) => ({ id: String(id), type, startedAt: '2026-01-01T00:01:00Z' })),
});
const labels = root => root.querySelectorAll('.scoreboard-notice').map(n => n.textContent);
const withDocument = action => {
  const old = globalThis.document;
  globalThis.document = { createElement: () => new Element() };
  try { action(); } finally { globalThis.document = old; }
};
test('confirmed pause types and permitted pairs render next to time without losing score', () => withDocument(() => {
  const root = new Element();
  for (const [types, expected] of [
    [['TIMEOUT'], ['Таймаут']], [['HALFTIME'], ['Халфтайм']], [['ANNOUNCEMENT'], ['Объявление']],
    [['TIMEOUT', 'ANNOUNCEMENT'], ['Таймаут', 'Объявление']],
    [['HALFTIME', 'ANNOUNCEMENT'], ['Халфтайм', 'Объявление']],
  ]) {
    const clock = renderScoreboard(root, sample(types));
    assert.deepEqual(labels(root), expected);
    assert.equal(root.querySelectorAll('.scoreboard-score')[0].textContent, '2 : 2');
    assert.equal(root.querySelectorAll('.scoreboard-timing')[0].children[0], clock);
    renderScoreboard(root, sample(types));
    assert.deepEqual(labels(root), expected, 'repeated snapshot does not duplicate notices');
  }
}));
test('phase is confirmed status only; legacy, planned and resumed have no pauses; finished suppresses stale pauses', () => withDocument(() => {
  const root = new Element();
  for (const state of [sample([], 'PLANNED'), sample(), { ...sample(), activePauses: undefined }]) {
    renderScoreboard(root, state); assert.deepEqual(labels(root), []);
  }
  renderScoreboard(root, sample(['TIMEOUT'], 'PLANNED')); assert.deepEqual(labels(root), []);
  renderScoreboard(root, sample(['TIMEOUT'], 'FINISHED')); assert.deepEqual(labels(root), ['Матч завершён']);
  renderScoreboard(root, sample(['UNRECOGNIZED'])); assert.deepEqual(labels(root), []);
}));
test('protected accept refreshes pause/status-only corrections, fresh/reconnect and resets AUTO/null/pagehide', () => withDocument(() => {
  const root = new Element(), stage = new Element(), status = new Element();
  let accept, tick, hide, stopped = false, mono = 0;
  const source = readFileSync(new URL('../../main/resources/static/obs/protected-scoreboard.mjs', import.meta.url), 'utf8')
    .replace(/^import .*;\n/gm, '');
  vm.runInNewContext(source, {
    document: { getElementById: id => ({ scoreboard: root, stage, status })[id] },
    ClockAnchor: class extends ClockAnchor { constructor() { super(() => mono); } }, renderScoreboard,
    parseSource: () => ({}), watchProtectedSource: (_source, callback) => { accept = callback; return () => { stopped = true; }; },
    URL, location: { href: 'https://fixture.invalid/' }, innerWidth: 1000, innerHeight: 160,
    addEventListener: (name, fn) => { if (name === 'pagehide') hide = fn; }, removeEventListener: () => {},
    setInterval: fn => { tick = fn; return 1; }, clearInterval: () => {},
  });
  accept(sample(['TIMEOUT'])); assert.deepEqual(labels(root), ['Таймаут']);
  accept(sample(['HALFTIME', 'ANNOUNCEMENT'])); assert.deepEqual(labels(root), ['Халфтайм', 'Объявление']);
  accept(sample(['HALFTIME', 'ANNOUNCEMENT'])); assert.equal(labels(root).length, 2);
  mono += 5000; tick(); assert.equal(root.querySelectorAll('.scoreboard-clock')[0].textContent, '02:05');
  accept(sample([], 'FINISHED')); assert.deepEqual(labels(root), ['Матч завершён']);
  mono += 5000; tick(); assert.equal(root.querySelectorAll('.scoreboard-clock')[0].textContent, '01:30');
  accept({ ...sample(), matchId: 'b' }); assert.deepEqual(labels(root), []);
  accept(null); assert.equal(root.hidden, true); assert.equal(root.children.length, 0);
  accept(sample(['ANNOUNCEMENT'])); assert.deepEqual(labels(root), ['Объявление']);
  accept(sample([], 'PLANNED')); assert.deepEqual(labels(root), []);
  assert.equal(root.querySelectorAll('.scoreboard-clock')[0].textContent, '00:00');
  hide(); accept(sample(['TIMEOUT'])); assert.equal(root.children.length, 0); assert.equal(stopped, true);
}));
