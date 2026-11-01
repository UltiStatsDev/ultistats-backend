import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const repository = resolve(import.meta.dirname, '../../..');
const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
async function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'stable162-build-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const source = join(root, 'source');
  git(root, 'clone', '--quiet', '--shared', '--no-hardlinks', repository, source);
  git(source, 'config', 'user.email', 'fixture@example.invalid');
  git(source, 'config', 'user.name', 'Synthetic Fixture');
  const installed = join(root, 'installed-sentinel');
  writeFileSync(installed, 'original-installation');
  const { inspectSource } = await import('../build.mjs');
  return { root, source, installed, inspectSource, sha: git(source, 'rev-parse', 'HEAD') };
}

test('exact clean candidate yields actual source and baseline product tree identities', async t => {
  const f = await fixture(t);
  const result = await f.inspectSource(f.source, f.sha);
  assert.equal(result.sourceSha, f.sha);
  assert.equal(result.baselineSha, '24704e11143a0bf919559a41324603f936ae03cf');
  assert.equal(result.productTrees['src/main'], '5c81f8910e958d1b28f1a096b59fcb700875b9f8');
  assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
});

test('wrong expected SHA refuses without modifying checkout or installed fixture', async t => {
  const f = await fixture(t);
  await assert.rejects(() => f.inspectSource(f.source, '24704e11143a0bf919559a41324603f936ae03cf'), /SOURCE_SHA_MISMATCH/);
  assert.equal(git(f.source, 'rev-parse', 'HEAD'), f.sha);
  assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
});

for (const name of ['tracked modification', 'untracked file', 'ignored file']) {
  test(`${name} refuses without deleting local data`, async t => {
    const f = await fixture(t);
    const file = name === 'tracked modification' ? 'Dockerfile' : name === 'ignored file' ? '.gitignore' : 'local-note';
    writeFileSync(join(f.source, file), name === 'ignored file' ? 'ignored-local\n' : 'local-data');
    if (name === 'ignored file') {
      git(f.source, 'add', '.gitignore');
      git(f.source, 'commit', '--quiet', '-m', 'fixture ignore');
      writeFileSync(join(f.source, 'ignored-local'), 'private-local-data');
      f.sha = git(f.source, 'rev-parse', 'HEAD');
    }
    await assert.rejects(() => f.inspectSource(f.source, f.sha), /SOURCE_NOT_CLEAN/);
    assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
    assert.equal(readFileSync(join(f.source, name === 'ignored file' ? 'ignored-local' : file), 'utf8'), name === 'ignored file' ? 'private-local-data' : 'local-data');
  });
}

test('committed product delta is refused despite a clean exact source SHA', async t => {
  const f = await fixture(t);
  writeFileSync(join(f.source, 'src/main/resources/db/migration/V10__unapproved.sql'), 'SELECT 1;');
  git(f.source, 'add', '.');
  git(f.source, 'commit', '--quiet', '-m', 'fixture unapproved migration');
  await assert.rejects(() => f.inspectSource(f.source, git(f.source, 'rev-parse', 'HEAD')), /BASELINE_PARITY_MISMATCH/);
  assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
});

test('abbreviated source identity is refused', async t => {
  const f = await fixture(t);
  await assert.rejects(() => f.inspectSource(f.source, f.sha.slice(0, 7)), /INVALID_SOURCE_SHA/);
});
