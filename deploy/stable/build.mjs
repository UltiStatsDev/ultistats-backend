import { execFileSync } from 'node:child_process';
import { realpathSync, mkdirSync, readFileSync, writeFileSync, lstatSync } from 'node:fs';
import { isAbsolute, resolve, dirname, join, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const BASELINE = '24704e11143a0bf919559a41324603f936ae03cf';
const PARITY_PATHS = ['src/main', 'src/test', 'build.gradle.kts', 'settings.gradle.kts', 'gradle', 'gradlew', 'Dockerfile'];
const gitArgs = args => ['--no-replace-objects', '--no-optional-locks', '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null', ...args];
const git = (cwd, ...args) => execFileSync('git', gitArgs(args), { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim();

function readonlyStatus(sourceDir) {
  let keys;
  try { keys = git(sourceDir, 'config', '--null', '--name-only', '--get-regexp', '^filter\\.'); }
  catch (error) { if (error.status !== 1) throw error; keys = ''; }
  const names = new Set(keys.split('\0').map(key => /^filter\.(.+)\.(clean|smudge|process|required)$/.exec(key)?.[1]).filter(Boolean));
  const overrides = [...names].flatMap(name => ['clean', 'smudge', 'process', 'required'].flatMap(field =>
    ['-c', `filter.${name}.${field}=${field === 'required' ? 'false' : ''}`]));
  return git(sourceDir, ...overrides, 'status', '--porcelain=v1', '--untracked-files=all', '--ignored=matching');
}

export async function inspectSource(sourceDir, expectedSha) {
  if (!/^[a-f0-9]{40}$/.test(expectedSha ?? '')) throw new Error('INVALID_SOURCE_SHA');
  if (realpathSync(sourceDir) !== realpathSync(git(sourceDir, 'rev-parse', '--show-toplevel'))) throw new Error('SOURCE_ROOT_MISMATCH');
  const sourceSha = git(sourceDir, 'rev-parse', 'HEAD');
  if (sourceSha !== expectedSha) throw new Error('SOURCE_SHA_MISMATCH');
  if (readonlyStatus(sourceDir)) throw new Error('SOURCE_NOT_CLEAN');
  const productTrees = {};
  for (const path of PARITY_PATHS) {
    const actual = git(sourceDir, 'rev-parse', `${sourceSha}:${path}`);
    if (actual !== git(sourceDir, 'rev-parse', `${BASELINE}:${path}`)) throw new Error('BASELINE_PARITY_MISMATCH');
    productTrees[path] = actual;
  }
  return { sourceSha, baselineSha: BASELINE, productTrees };
}

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const inside = (path, parent) => path === parent || path.startsWith(parent + sep);
const imageId = value => /^sha256:[a-f0-9]{64}$/.test(value ?? '');
const dockerCommand = args => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 1800000, maxBuffer: 16 * 1024 * 1024 }).trim();

export async function buildSource(request, { docker = dockerCommand } = {}) {
  const { sourceDir, expectedSourceSha, stageDir, imageTag, ownership } = request ?? {};
  if (request?.schemaVersion !== 1 || !ownership || !/^[a-f0-9]{12}$/.test(ownership.instanceId ?? '') ||
      !['stable', 'dev'].includes(ownership.mode) || ownership.project !== `ultistats-${ownership.mode}-${ownership.instanceId}` ||
      imageTag !== `${ownership.project}-backend:${expectedSourceSha}`) throw new Error('INVALID_OWNERSHIP');
  if (!isAbsolute(sourceDir ?? '') || !isAbsolute(stageDir ?? '')) throw new Error('INVALID_STAGE_DIR');
  const source = realpathSync(sourceDir);
  const stage = join(realpathSync(dirname(stageDir)), stageDir.split(sep).at(-1));
  if (stage !== resolve(stageDir) || inside(stage, source) || inside(source, stage)) throw new Error('INVALID_STAGE_DIR');
  const identity = await inspectSource(source, expectedSourceSha);
  if (docker(['image', 'ls', '--quiet', '--no-trunc', '--filter', `reference=${imageTag}`]).trim()) throw new Error('IMAGE_TAG_EXISTS');
  // mkdir is exclusive: never reuse, erase or overwrite another staging directory.
  mkdirSync(stage, { mode: 0o700 });
  const snapshot = join(stage, 'source');
  mkdirSync(snapshot, { mode: 0o700 });
  // Raw object reads never run checkout/smudge/export transformations. In particular,
  // local replace refs cannot redefine the accepted baseline or the build context.
  const entries = git(source, 'ls-tree', '-rz', '--full-tree', identity.sourceSha).split('\0').filter(Boolean).map(entry => {
    const match = /^(100644|100755) blob ([a-f0-9]{40})\t(.+)$/s.exec(entry);
    if (!match || !inside(resolve(snapshot, match[3]), snapshot) || match[3].split('/').some(p => ['.', '..', '.git'].includes(p))) throw new Error('UNSUPPORTED_SOURCE_ENTRY');
    return { mode: match[1], oid: match[2], path: match[3] };
  });
  const objects = execFileSync('git', gitArgs(['cat-file', '--batch']), { cwd: source,
    input: entries.map(e => e.oid + '\n').join(''), timeout: 30000, maxBuffer: 256 * 1024 * 1024 });
  let offset = 0;
  for (const entry of entries) {
    const end = objects.indexOf(10, offset);
    const header = objects.subarray(offset, end).toString('ascii').split(' ');
    const size = Number(header[2]);
    if (end < offset || header[0] !== entry.oid || header[1] !== 'blob' || !Number.isSafeInteger(size) || size < 0 || end + size + 1 >= objects.length) throw new Error('SOURCE_OBJECT_INVALID');
    const bytes = objects.subarray(end + 1, end + 1 + size);
    const digest = createHash('sha1').update(`blob ${size}\0`).update(bytes).digest('hex');
    if (digest !== entry.oid || objects[end + 1 + size] !== 10) throw new Error('SOURCE_OBJECT_INVALID');
    const destination = resolve(snapshot, entry.path);
    mkdirSync(dirname(destination), { recursive: true, mode: 0o700 });
    writeFileSync(destination, bytes, { mode: entry.mode === '100755' ? 0o700 : 0o600, flag: 'wx' });
    offset = end + size + 2;
  }
  if (offset !== objects.length) throw new Error('SOURCE_OBJECT_INVALID');
  const dockerfile = readFileSync(join(snapshot, 'Dockerfile'), 'utf8');
  const toolchain = {};
  let generated = dockerfile;
  for (const [key, ref] of [['builder', 'gradle:8.14-jdk21'], ['runtime', 'eclipse-temurin:21-jre-alpine']]) {
    let data;
    try { data = JSON.parse(docker(['image', 'inspect', ref]))[0]; }
    catch { docker(['pull', ref]); data = JSON.parse(docker(['image', 'inspect', ref]))[0]; }
    const digest = data?.RepoDigests?.find(value => /^[a-z0-9./_-]+@sha256:[a-f0-9]{64}$/.test(value));
    if (!imageId(data?.Id) || !digest) throw new Error('TOOLCHAIN_IDENTITY_MISSING');
    toolchain[`${key}Ref`] = ref;
    toolchain[`${key}ImageId`] = data.Id;
    toolchain[`${key}Digest`] = digest;
    generated = generated.replace(`FROM ${ref}`, `FROM ${digest}`);
  }
  // Reviewed build-only delta in staging; product source and tracked Dockerfile stay intact.
  generated = generated.replaceAll('--no-daemon', '--no-daemon --max-workers=2');
  const generatedPath = join(stage, 'Dockerfile.build');
  writeFileSync(generatedPath, generated, { mode: 0o600, flag: 'wx' });
  docker(['build', '--pull=false', '--file', generatedPath, '--label', `io.ultistats.instance=${ownership.instanceId}`,
    '--label', `org.opencontainers.image.revision=${identity.sourceSha}`, '--tag', imageTag, snapshot]);
  const image = JSON.parse(docker(['image', 'inspect', imageTag]))[0];
  if (!imageId(image?.Id) || image.Config?.Labels?.['io.ultistats.instance'] !== ownership.instanceId ||
      image.Config?.Labels?.['org.opencontainers.image.revision'] !== identity.sourceSha) throw new Error('IMAGE_IDENTITY_MISMATCH');
  let container;
  const jar = join(stage, 'app.jar');
  try {
    container = docker(['create', '--network', 'none', '--label', `io.ultistats.instance=${ownership.instanceId}`,
      '--label', `org.opencontainers.image.revision=${identity.sourceSha}`, image.Id]).trim();
    if (!/^[a-f0-9]{64}$/.test(container)) { container = undefined; throw new Error('ARTIFACT_CONTAINER_ID_INVALID'); }
    docker(['cp', `${container}:/app/app.jar`, jar]);
  } finally {
    if (container) docker(['rm', container]);
  }
  const stat = lstatSync(jar);
  if (!stat.isFile() || stat.size === 0) throw new Error('JAR_EVIDENCE_MISSING');
  const result = { schemaVersion: 1, sourceSha: identity.sourceSha, imageTag, imageId: image.Id,
    jarSha256: hash(readFileSync(jar)), productTree: identity.productTrees['src/main'],
    DockerfileSha256: hash(dockerfile), toolchain, stageDir: stage,
    generatedDockerfileSha256: hash(generated), baselineSha: BASELINE, productTrees: identity.productTrees };
  writeFileSync(join(stage, 'build-result.json'), JSON.stringify(result, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  return result;
}

function privateInput(path) {
  if (!isAbsolute(path ?? '')) throw new Error('PRIVATE_PATH_REQUIRED');
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.uid !== process.getuid?.() || (stat.mode & 0o077)) throw new Error('PRIVATE_INPUT_REQUIRED');
  return JSON.parse(readFileSync(path, 'utf8'));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  let resultPath;
  let resultValidated = false;
  try {
    if (process.argv.length !== 6 || process.argv[2] !== '--request' || process.argv[4] !== '--result') throw new Error('USAGE');
    resultPath = process.argv[5];
    if (!isAbsolute(resultPath) || resolve(resultPath) === resolve(process.argv[3])) throw new Error('PRIVATE_PATH_REQUIRED');
    const parent = lstatSync(dirname(resultPath));
    if (!parent.isDirectory() || parent.uid !== process.getuid?.() || (parent.mode & 0o077)) throw new Error('PRIVATE_RESULT_DIRECTORY_REQUIRED');
    resultValidated = true;
    const request = privateInput(process.argv[3]);
    const result = await buildSource(request);
    writeFileSync(resultPath, JSON.stringify(result) + '\n', { mode: 0o600, flag: 'wx' });
  } catch (error) {
    const reason = /^[A-Z][A-Z_]+$/.test(error.message ?? '') ? error.message : 'BUILD_FAILED';
    // Never print command stderr or private request content.
    process.stderr.write(`${reason}\n`);
    if (resultValidated) {
      try { writeFileSync(resultPath, JSON.stringify({ schemaVersion: 1, status: 'blocked', reason }) + '\n', { mode: 0o600, flag: 'wx' }); } catch {}
    }
    process.exitCode = 1;
  }
}
