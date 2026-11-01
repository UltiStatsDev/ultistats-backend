import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
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
