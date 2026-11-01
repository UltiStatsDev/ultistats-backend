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

function request(f) {
  return { schemaVersion: 1, sourceDir: f.source, expectedSourceSha: f.sha,
    stageDir: join(f.root, 'stage'), imageTag: `ultistats-stable-000000000162-backend:${f.sha}`,
    ownership: { instanceId: '000000000162', mode: 'stable', project: 'ultistats-stable-000000000162' } };
}
function dockerFixture(f, failure) {
  return (args) => {
    if (args[0] === 'image' && args[1] === 'ls') return '';
    if (args[0] === 'image' && args[1] === 'inspect') {
      if (args[2].startsWith('gradle:')) return JSON.stringify([{ Id: `sha256:${'1'.repeat(64)}`, RepoDigests: [`gradle@sha256:${'a'.repeat(64)}`] }]);
      if (args[2].startsWith('eclipse-temurin:')) return JSON.stringify([{ Id: `sha256:${'2'.repeat(64)}`, RepoDigests: [`eclipse-temurin@sha256:${'b'.repeat(64)}`] }]);
      return JSON.stringify([{ Id: `sha256:${'3'.repeat(64)}`, Config: { Labels: {
        'io.ultistats.instance': '000000000162', 'org.opencontainers.image.revision': failure === 'label' ? 'unknown' : f.sha } } }]);
    }
    if (args[0] === 'build') {
      if (failure === 'build') throw new Error('upstream build failed with private details');
      return '';
    }
    if (args[0] === 'create') return '4'.repeat(64);
    if (args[0] === 'cp') {
      if (failure === 'jar') throw new Error('jar unavailable');
      writeFileSync(args[2], 'synthetic-jar-content'); return '';
    }
    if (args[0] === 'rm') return '';
    throw new Error(`unexpected Docker operation ${args[0]}`);
  };
}

test('build stages exact source and returns verified immutable image and JAR evidence', async t => {
  const f = await fixture(t);
  const { buildSource } = await import('../build.mjs');
  const result = await buildSource(request(f), { docker: dockerFixture(f) });
  assert.equal(result.sourceSha, f.sha);
  assert.equal(result.imageId, `sha256:${'3'.repeat(64)}`);
  assert.match(result.jarSha256, /^[a-f0-9]{64}$/);
  assert.equal(result.productTree, '5c81f8910e958d1b28f1a096b59fcb700875b9f8');
  assert.equal(readFileSync(join(f.root, 'stage/source/Dockerfile'), 'utf8'), git(f.source, 'show', 'HEAD:Dockerfile') + '\n');
  assert.equal(git(f.source, 'status', '--porcelain'), '');
  assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
});
for (const failure of ['build', 'label', 'jar']) {
  test(`${failure} failure leaves installed data and source untouched`, async t => {
    const f = await fixture(t);
    const { buildSource } = await import('../build.mjs');
    await assert.rejects(() => buildSource(request(f), { docker: dockerFixture(f, failure) }));
    assert.equal(git(f.source, 'status', '--porcelain'), '');
    assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
  });
}

test('stage path inside source refuses before writing or calling Docker', async t => {
  const f = await fixture(t);
  const { buildSource } = await import('../build.mjs');
  await assert.rejects(() => buildSource({ ...request(f), stageDir: join(f.source, 'stage') }, {
    docker: () => assert.fail('Docker must not run')
  }), /INVALID_STAGE_DIR/);
  assert.equal(git(f.source, 'status', '--porcelain'), '');
});

test('unowned image tag refuses before calling Docker', async t => {
  const f = await fixture(t);
  const { buildSource } = await import('../build.mjs');
  await assert.rejects(() => buildSource({ ...request(f), imageTag: 'foreign:latest' }, {
    docker: () => assert.fail('Docker must not run')
  }), /INVALID_OWNERSHIP/);
});

test('existing image tag refuses without replacing an installed image', async t => {
  const f = await fixture(t);
  const { buildSource } = await import('../build.mjs');
  await assert.rejects(() => buildSource(request(f), { docker: args => {
    if (args[0] === 'image' && args[1] === 'ls') return `sha256:${'9'.repeat(64)}`;
    assert.fail('Existing image must not be mutated');
  } }), /IMAGE_TAG_EXISTS/);
});

test('failed toolchain acquisition preserves source and installed data', async t => {
  const f = await fixture(t);
  const { buildSource } = await import('../build.mjs');
  await assert.rejects(() => buildSource(request(f), { docker: args => {
    if (args[0] === 'image' && args[1] === 'ls') return '';
    throw new Error('private registry diagnostic');
  } }));
  assert.equal(readFileSync(f.installed, 'utf8'), 'original-installation');
  assert.equal(git(f.source, 'status', '--porcelain'), '');
});

test('CLI refuses a public result directory without creating a result there', async t => {
  const f = await fixture(t);
  const input = join(f.root, 'request.json');
  writeFileSync(input, JSON.stringify(request(f)), { mode: 0o600 });
  const publicResult = join(tmpdir(), `stable162-unsafe-${process.pid}.json`);
  t.after(() => rmSync(publicResult, { force: true }));
  assert.throws(() => execFileSync(process.execPath, [join(repository, 'deploy/stable/build.mjs'), '--request', input, '--result', publicResult], { stdio: 'pipe' }));
  assert.throws(() => readFileSync(publicResult), { code: 'ENOENT' });
});

test('CLI malformed private request yields sanitized private blocked result', async t => {
  const f = await fixture(t);
  const input = join(f.root, 'request.json');
  const output = join(f.root, 'result.json');
  writeFileSync(input, '{private malformed token', { mode: 0o600 });
  let failure;
  const env = { ...process.env }; delete env.NODE_TEST_CONTEXT;
  try { execFileSync(process.execPath, [join(repository, 'deploy/stable/build.mjs'), '--request', input, '--result', output], { encoding: 'utf8', stdio: 'pipe', env }); }
  catch (error) { failure = error; }
  assert.equal(failure.status, 1);
  assert.equal(failure.stderr, 'BUILD_FAILED\n');
  assert.deepEqual(JSON.parse(readFileSync(output)), { schemaVersion: 1, status: 'blocked', reason: 'BUILD_FAILED' });
});

test('local Git replacement cannot redefine the fixed baseline to accept V10', async t => {
  const f = await fixture(t);
  writeFileSync(join(f.source, 'src/main/resources/db/migration/V10__unapproved.sql'), 'SELECT 1;');
  git(f.source, 'add', '.');
  git(f.source, 'commit', '--quiet', '-m', 'fixture unapproved product delta');
  const sha = git(f.source, 'rev-parse', 'HEAD');
  await assert.rejects(() => f.inspectSource(f.source, sha), /BASELINE_PARITY_MISMATCH/);
  const replacement = git(f.source, 'commit-tree', 'HEAD^{tree}', '-m', 'fixture replacement');
  git(f.source, 'replace', '24704e11143a0bf919559a41324603f936ae03cf', replacement);
  await assert.rejects(() => f.inspectSource(f.source, sha), /BASELINE_PARITY_MISMATCH/);
});

test('snapshot ignores local smudge filters and contains exact committed product bytes', async t => {
  const f = await fixture(t);
  const target = 'src/main/resources/static/obs/obs.css';
  const attr = join(f.source, '.gitattributes');
  writeFileSync(attr, readFileSync(attr, 'utf8') + `\n${target} filter=syntheticcanary\n`);
  git(f.source, 'config', 'filter.syntheticcanary.clean', 'cat');
  git(f.source, 'config', 'filter.syntheticcanary.smudge', "cat; printf '\\nSYNTHETIC_PRIVATE_CANARY\\n'");
  git(f.source, 'add', '.gitattributes');
  git(f.source, 'commit', '--quiet', '-m', 'fixture checkout transformation');
  f.sha = git(f.source, 'rev-parse', 'HEAD');
  const committed = execFileSync('git', ['show', `${f.sha}:${target}`], { cwd: f.source });
  const { buildSource } = await import('../build.mjs');
  const docker = dockerFixture(f);
  await buildSource(request(f), { docker: args => {
    if (args[0] === 'build') assert.deepEqual(readFileSync(join(args.at(-1), target)), committed);
    return docker(args);
  } });
});

test('readonly source preflight does not execute configured clean filter programs', async t => {
  const f = await fixture(t);
  const target = 'src/main/resources/static/obs/obs.css';
  const marker = join(f.root, 'clean-filter-executed');
  const attr = join(f.source, '.gitattributes');
  writeFileSync(attr, readFileSync(attr, 'utf8') + `\n${target} filter=syntheticclean\n`);
  git(f.source, 'config', 'filter.syntheticclean.clean', `touch '${marker}'; cat`);
  git(f.source, 'config', 'filter.syntheticclean.smudge', 'cat');
  git(f.source, 'add', '.gitattributes');
  git(f.source, 'commit', '--quiet', '-m', 'fixture clean filter');
  rmSync(marker, { force: true });
  // Force Git to rehash an unchanged tracked file rather than trust index stat data.
  writeFileSync(join(f.source, target), readFileSync(join(f.source, target)));
  await f.inspectSource(f.source, git(f.source, 'rev-parse', 'HEAD'));
  assert.throws(() => readFileSync(marker), { code: 'ENOENT' });
});
