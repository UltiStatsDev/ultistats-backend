import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
const api = () => import('./promotion.mjs');
function fixture(t) {
  const repo = mkdtempSync(join(tmpdir(), 'stable160-'));
  t.after(() => rmSync(repo, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '-b', 'main'); git('config', 'user.name', 'Synthetic'); git('config', 'user.email', 'synthetic@example.invalid');
  writeFileSync(join(repo, 'source'), 'accepted\n'); git('add', '.'); git('commit', '-m', 'accepted');
  return { repo, git, sha: git('rev-parse', 'HEAD') };
}
test('missing exact tag is rejected without creating refs', async t => {
  const { repo, git, sha } = fixture(t); const { readExactTag } = await api();
  assert.throws(() => readExactTag(repo, 'v0.1.0', sha), /missing/i);
  assert.equal(git('tag'), '');
});
test('full source SHA must identify a commit, not abbreviation or blob', async t => {
  const { repo, git, sha } = fixture(t); const { verifySource } = await api();
  assert.equal(verifySource(repo, sha), sha);
  assert.throws(() => verifySource(repo, sha.slice(0, 7)), /full|sha/i);
  assert.throws(() => verifySource(repo, git('rev-parse', 'HEAD:source')), /commit/i);
});
test('lightweight exact tag remains accepted when main advances', async t => {
  const { repo, git, sha } = fixture(t); git('tag', 'v0.1.0', sha);
  writeFileSync(join(repo, 'source'), 'unfinished\n'); git('commit', '-am', 'unfinished main');
  const { readExactTag } = await api();
  assert.equal(readExactTag(repo, 'v0.1.0', sha), sha);
  assert.notEqual(git('rev-parse', 'HEAD'), sha);
});
test('tag mutation rejects exact previously accepted source', async t => {
  const { repo, git, sha } = fixture(t); git('tag', 'v0.1.0', sha);
  writeFileSync(join(repo, 'source'), 'changed\n'); git('commit', '-am', 'changed'); git('tag', '-f', 'v0.1.0');
  const { readExactTag } = await api(); const refs = git('show-ref');
  assert.throws(() => readExactTag(repo, 'v0.1.0', sha), /conflict|mismatch/i);
  assert.equal(git('show-ref'), refs);
});
test('annotated tags are rejected even when peeled commit matches', async t => {
  const { repo, git, sha } = fixture(t); git('tag', '-a', 'v0.1.0', '-m', 'not lightweight');
  const { readExactTag } = await api();
  assert.throws(() => readExactTag(repo, 'v0.1.0', sha), /lightweight/i);
});
test('planning interrupted publication is readonly and exact retry is noop', async t => {
  const { repo, git, sha } = fixture(t); const { planTag } = await api();
  assert.equal(planTag(repo, 'v0.1.0', sha).action, 'create'); assert.equal(git('tag'), '');
  git('tag', 'v0.1.0', sha);
  assert.equal(planTag(repo, 'v0.1.0', sha).action, 'noop');
  assert.equal(planTag(repo, 'v0.1.0', sha).action, 'noop');
  writeFileSync(join(repo, 'source'), 'new\n'); git('commit', '-am', 'new');
  assert.throws(() => planTag(repo, 'v0.1.0', git('rev-parse', 'HEAD')), /conflict|mismatch/i);
  assert.equal(git('rev-parse', 'refs/tags/v0.1.0'), sha);
});
test('invalid versions and option-like revisions fail closed', async t => {
  const { repo, sha } = fixture(t); const { planTag, verifySource } = await api();
  for (const tag of ['main', 'v01.1.0', 'v0.1', 'v0.1.0-rc.1', '--help', 'v0.1.0/other']) {
    assert.throws(() => planTag(repo, tag, sha), /version|tag/i);
  }
  assert.throws(() => verifySource(repo, '--help'), /sha/i);
});

const BASE_WEB = '39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba';
const BASE_BACKEND = '24704e11143a0bf919559a41324603f936ae03cf';
function pair(t) {
  const backend = fixture(t); const web = fixture(t);
  mkdirSync(join(web.repo, 'dev/backend'), { recursive: true });
  writeFileSync(join(web.repo, 'dev/backend/revision'), `${backend.sha}\n`);
  web.git('add', '.'); web.git('commit', '-m', 'pin'); web.sha = web.git('rev-parse', 'HEAD');
  const record = {
    schemaVersion: 1, status: 'finalized', version: '0.1.0',
    repositories: {
      web: { repository: 'UltiStatsDev/ultistats-web-ui', commit: web.sha, tag: 'v0.1.0' },
      backend: { repository: 'UltiStatsDev/ultistats-backend', commit: backend.sha, tag: 'v0.1.0' },
    },
    backendPin: backend.sha,
    compatibility: { fromSchema: 9, toSchema: 9, appRollback: true, supportedSourcePairs: [{ web: BASE_WEB, backend: BASE_BACKEND }] },
    prerequisites: { git: '>=2.39', node: '>=22', docker: '>=24', compose: '>=2.20' },
    evidence: { qa: 'synthetic', po: 'synthetic' }, notes: 'Synthetic', update: './update.sh', rollback: 'Proven app rollback only',
  };
  return { backend, web, record, repos: { webRepo: web.repo, backendRepo: backend.repo } };
}
test('one-sided refs fail until both exact application tags exist', async t => {
  const { backend, web, record, repos } = pair(t); const { verifyPairSources } = await api();
  backend.git('tag', 'v0.1.0', backend.sha);
  assert.throws(() => verifyPairSources(record, repos), /missing/i);
  web.git('tag', 'v0.1.0', web.sha);
  const before = [backend.git('show-ref'), web.git('show-ref')];
  assert.equal(verifyPairSources(record, repos).version, '0.1.0');
  assert.equal(verifyPairSources(record, repos).version, '0.1.0');
  assert.deepEqual([backend.git('show-ref'), web.git('show-ref')], before);
});
test('producer rejects absent/draft descriptor and pin mismatch', async t => {
  const { backend, record, repos } = pair(t); const { verifyBackend } = await api();
  backend.git('tag', 'v0.1.0', backend.sha);
  assert.throws(() => verifyBackend(null, repos), /record/i);
  assert.throws(() => verifyBackend({ ...record, status: 'draft' }, repos), /finalized/i);
  assert.throws(() => verifyBackend({ ...record, backendPin: BASE_BACKEND }, repos), /pin/i);
  assert.throws(() => verifyBackend({ ...record, schemaVersion: 2 }, repos), /schema|version/i);
});
test('producer blocks unknown V9 sources and future schema/rollback declarations', async t => {
  const { backend, record, repos } = pair(t); const { verifyBackend } = await api(); backend.git('tag', 'v0.1.0');
  for (const compatibility of [
    { ...record.compatibility, fromSchema: 10 }, { ...record.compatibility, toSchema: 10 },
    { ...record.compatibility, appRollback: false }, { ...record.compatibility, supportedSourcePairs: [] },
    { ...record.compatibility, supportedSourcePairs: [{ web: BASE_WEB, backend: backend.sha }] },
  ]) assert.throws(() => verifyBackend({ ...record, compatibility }, repos), /compatibility|supported|proven/i);
});
test('pair verifies pin at application source even when metadata/main pin changes', async t => {
  const { backend, web, record, repos } = pair(t); const { verifyPairSources } = await api();
  backend.git('tag', 'v0.1.0'); web.git('tag', 'v0.1.0');
  writeFileSync(join(web.repo, 'dev/backend/revision'), `${BASE_BACKEND}\n`); web.git('commit', '-am', 'moving main wrong pin');
  assert.equal(verifyPairSources(record, repos).version, '0.1.0');
  const bad = { ...record, repositories: { ...record.repositories, web: { ...record.repositories.web, commit: web.git('rev-parse', 'HEAD'), tag: 'v0.1.1' }, backend: { ...record.repositories.backend, tag: 'v0.1.1' } }, version: '0.1.1' };
  web.git('tag', 'v0.1.1'); backend.git('tag', 'v0.1.1');
  assert.throws(() => verifyPairSources(bad, repos), /pin/i);
});
test('canonical repository/tag fields cannot be substituted', async t => {
  const { backend, record, repos } = pair(t); const { verifyBackend } = await api(); backend.git('tag', 'v0.1.0');
  for (const entry of [{ ...record.repositories.backend, repository: 'other/backend' }, { ...record.repositories.backend, tag: 'v0.2.0' }]) {
    assert.throws(() => verifyBackend({ ...record, repositories: { ...record.repositories, backend: entry } }, repos), /repository|tag/i);
  }
});

test('CLI verifies explicit local pair and emits only sanitized failure', async t => {
  const { backend, web, record, repos } = pair(t); const { runCli } = await api();
  const recordPath = join(web.repo, 'record.json'); writeFileSync(recordPath, JSON.stringify(record));
  const args = ['verify', '--record', recordPath, '--web-repo', repos.webRepo, '--backend-repo', repos.backendRepo];
  const output = []; const emit = data => output.push(JSON.parse(data));
  assert.equal(runCli(args, emit), 1); assert.equal(output[0].ok, false);
  backend.git('tag', 'v0.1.0'); web.git('tag', 'v0.1.0');
  assert.equal(runCli(args, emit), 0);
  assert.deepEqual(output.at(-1), { ok: true, version: '0.1.0', backend: { commit: backend.sha, tag: 'v0.1.0' }, compatibility: record.compatibility });
  writeFileSync(recordPath, 'private-invalid-json-token');
  assert.equal(runCli(args, emit), 1);
  assert.equal(JSON.stringify(output).includes('private-invalid-json-token'), false);
  assert.equal(runCli([...args, '--force'], emit), 1);
  assert.equal(runCli([...args, '--record', recordPath], emit), 1);
});

test('explicit repository identity is unaffected by ambient Git directory overrides', async t => {
  const actual = fixture(t); const other = fixture(t); const { readExactTag } = await api();
  actual.git('tag', 'v0.1.0');
  const prior = process.env.GIT_DIR;
  process.env.GIT_DIR = join(other.repo, '.git');
  try { assert.equal(readExactTag(actual.repo, 'v0.1.0', actual.sha), actual.sha); }
  finally { if (prior === undefined) delete process.env.GIT_DIR; else process.env.GIT_DIR = prior; }
});
