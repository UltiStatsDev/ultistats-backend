import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSource, watchProtectedSource, validateScoreboard } from '../../main/resources/static/obs/protected-source-client.mjs';
const id = 'abcdefab-cdef-abcd-efab-cdefabcdefab',
  other = 'ffffffff-ffff-ffff-ffff-ffffffffffff',
  key = 'a'.repeat(43);
const url = (query = `matchId=${id}`, fragment = `key=${key}`) =>
  new URL(`https://localhost/obs-sources/scoreboard.html?${query}#${fragment}`);
const sample = () => ({
  matchId: id,
  status: 'IN_PROGRESS',
  startedAt: '2026-01-01T00:00:00Z',
  endedAt: null,
  serverNow: '2026-01-01T00:01:00Z',
  teams: [
    { teamId: id, teamName: 'A', score: 1, photoUrl: null },
    { teamId: other, teamName: 'B', score: 2, photoUrl: '/uploads/b.png' },
  ],
  profile: { swapped: false, shortNames: {}, teamColors: {} },
});
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
test('strict exact/auto binding accepts only fragment key and HTTPS canonical generated URL', () => {
  assert.equal(parseSource(url()).path, `/api/v1/obs/sources/scoreboard/${id}`);
  assert.equal(parseSource(url('mode=auto')).path, '/api/v1/obs/sources/scoreboard/auto');
  assert.equal(parseSource(url(`matchId=${id.toUpperCase()}`)).matchId, id);
  for (const u of [
    url(`matchId=${id}&key=${key}`),
    url(`matchId=${id}&mode=auto`),
    url(`matchId=${id}&matchId=${other}`),
    url('mode=AUTO'),
    url('', `key=${key}`),
    url(undefined, 'key=bad'),
    url(undefined, `key=${key}&key=${key}`),
    new URL(url().href.replace('https:', 'http:')),
  ])
    assert.throws(() => parseSource(u));
});
test('strict scoreboard identity teams profile and timing reject malformed data', () => {
  assert.equal(validateScoreboard(sample(), id).matchId, id);
  assert.equal(
    validateScoreboard({ ...sample(), teams: sample().teams.map((t) => ({ ...t, photoUrl: 'https://other.test/photo' })) }, id).teams[0]
      .photoUrl,
    null,
  );
  for (const value of [
    { ...sample(), matchId: other },
    { ...sample(), teams: [sample().teams[0], sample().teams[0]] },
    { ...sample(), profile: { swapped: false, shortNames: [], teamColors: {} } },
    { ...sample(), profile: { swapped: false, shortNames: { [other]: 5 }, teamColors: {} } },
    { ...sample(), status: 'FINISHED', endedAt: null },
  ])
    assert.throws(() => validateScoreboard(value, id));
});
test('serialized credentialless protected polling survives transient failure and late correction then terminal revoke clears and stops', async () => {
  let calls = 0,
    active = 0,
    max = 0,
    terminal = false;
  const seen = [],
    statuses = [];
  const stop = watchProtectedSource(
    parseSource(url()),
    (v) => seen.push(v),
    (s) => statuses.push(s),
    {
      pollMs: 3,
      retryMs: 3,
      fetch: async (path, options) => {
        calls++;
        active++;
        max = Math.max(max, active);
        assert.equal(path, `/api/v1/obs/sources/scoreboard/${id}`);
        assert.equal(options.credentials, 'omit');
        assert.equal(options.cache, 'no-store');
        assert.equal(options.redirect, 'error');
        assert.equal(options.headers.Authorization, `Bearer ${key}`);
        await pause(2);
        active--;
        if (calls === 2) throw Error('secret must not reach feedback');
        if (terminal) return { ok: false, status: 401 };
        return {
          ok: true,
          json: async () => ({
            ...sample(),
            status: 'FINISHED',
            endedAt: '2026-01-01T00:01:00Z',
            teams: sample().teams.map((t) => ({ ...t, score: calls })),
          }),
        };
      },
    },
  );
  await pause(24);
  assert.equal(max, 1);
  assert.ok(seen.length > 1);
  assert.ok(statuses.includes('Табло: переподключение…'));
  terminal = true;
  await pause(15);
  assert.equal(seen.at(-1), null);
  const count = calls;
  await pause(12);
  assert.equal(calls, count);
  assert.ok(!statuses.join().includes('secret'));
  stop();
});
test('auto null envelope and switch validate selected identity; disposal drops late response and aborts', async () => {
  const seen = [];
  let resolve, signal;
  const stop = watchProtectedSource(
    parseSource(url('mode=auto')),
    (v) => seen.push(v),
    () => {},
    {
      pollMs: 10000,
      fetch: async (_p, o) => {
        signal = o.signal;
        return new Promise((r) => (resolve = r));
      },
    },
  );
  await pause(0);
  stop();
  assert.equal(signal.aborted, true);
  resolve({ ok: true, json: async () => ({ snapshot: sample() }) });
  await pause(0);
  assert.deepEqual(seen, []);
  for (const snapshot of [null, sample(), { ...sample(), matchId: other }]) {
    const results = [];
    const end = watchProtectedSource(
      parseSource(url('mode=auto')),
      (v) => results.push(v),
      () => {},
      { pollMs: 10000, fetch: async () => ({ ok: true, json: async () => ({ snapshot }) }) },
    );
    await pause(0);
    end();
    assert.deepEqual(results, [snapshot === null ? null : validateScoreboard(snapshot, snapshot.matchId)]);
  }
});
test('timeout aborts and bounded retry stops cleanly', async () => {
  let calls = 0;
  const stop = watchProtectedSource(
    parseSource(url()),
    () => {},
    () => {},
    {
      timeoutMs: 3,
      retryMs: 3,
      fetch: async (_p, { signal }) => {
        calls++;
        return new Promise((_r, reject) => signal.addEventListener('abort', () => reject(Error('timeout'))));
      },
    },
  );
  await pause(18);
  assert.ok(calls >= 2);
  stop();
  const count = calls;
  await pause(10);
  assert.equal(calls, count);
});

test('all final access failures clear state and never retry; malformed auto envelope cannot select a match', async () => {
  for (const status of [401, 403, 404]) {
    const seen = [],
      statuses = [];
    let calls = 0;
    const stop = watchProtectedSource(
      parseSource(url()),
      (v) => seen.push(v),
      (s) => statuses.push(s),
      {
        pollMs: 1,
        retryMs: 1,
        fetch: async () => {
          calls++;
          return { ok: false, status };
        },
      },
    );
    await pause(5);
    stop();
    assert.deepEqual(seen, [null]);
    assert.equal(calls, 1);
    assert.deepEqual(statuses, ['Ссылка недоступна']);
  }
  const seen = [];
  const stop = watchProtectedSource(
    parseSource(url('mode=auto')),
    (v) => seen.push(v),
    () => {},
    {
      retryMs: 10000,
      fetch: async () => ({ ok: true, json: async () => sample() }),
    },
  );
  await pause(0);
  stop();
  assert.deepEqual(seen, []);
});
