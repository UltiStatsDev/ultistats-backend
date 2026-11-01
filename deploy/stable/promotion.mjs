import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const SHA = /^[0-9a-f]{40}$/;
const TAG = /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
function gitEnvironment() {
  // Explicit local repository arguments must not be redirected by an updater's
  // inherited GIT_DIR, object store, namespace or command config environment.
  return {
    ...Object.fromEntries(Object.entries(process.env).filter(([key]) => !key.startsWith('GIT_'))),
    GIT_TERMINAL_PROMPT: '0',
  };
}
function git(repository, ...args) {
  try {
    return execFileSync('git', ['--no-replace-objects', '-C', repository, ...args], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15000,
      env: gitEnvironment(),
    }).trim();
  } catch {
    throw new Error('Git source/ref verification failed');
  }
}
function checkTag(tag) {
  if (typeof tag !== 'string' || !TAG.test(tag)) throw new Error('Invalid stable version tag');
}
export function verifySource(repository, sourceSha) {
  if (typeof sourceSha !== 'string' || !SHA.test(sourceSha)) throw new Error('Expected full lowercase source SHA');
  if (git(repository, 'cat-file', '-t', sourceSha) !== 'commit') throw new Error('Source SHA must identify a commit');
  return sourceSha;
}
function tagTarget(repository, tag) {
  checkTag(tag);
  const ref = `refs/tags/${tag}`;
  // show-ref exit 1 is an expected absent ref; other errors must not become "create".
  try {
    return execFileSync('git', ['--no-replace-objects', '-C', repository, 'show-ref', '--verify', '--hash', ref], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15000,
      env: gitEnvironment(),
    }).trim();
  } catch (error) {
    if (error.status === 1) {
      git(repository, 'rev-parse', '--git-dir');
      return null;
    }
    // show-ref without --quiet returns 128 for absent refs on some Git versions.
    if (error.status === 128 && String(error.stderr).includes('not a valid ref')) {
      git(repository, 'rev-parse', '--git-dir');
      return null;
    }
    throw new Error('Git tag lookup failed');
  }
}
export function readExactTag(repository, tag, sourceSha) {
  verifySource(repository, sourceSha);
  const target = tagTarget(repository, tag);
  if (target === null) throw new Error('Missing application tag');
  if (git(repository, 'cat-file', '-t', target) !== 'commit') throw new Error('Application tag must be lightweight');
  if (target !== sourceSha) throw new Error('Immutable application tag mismatch/conflict');
  return sourceSha;
}
export function planTag(repository, tag, sourceSha) {
  verifySource(repository, sourceSha);
  const target = tagTarget(repository, tag);
  if (target === null) return { action: 'create', tag, commit: sourceSha };
  readExactTag(repository, tag, sourceSha);
  return { action: 'noop', tag, commit: sourceSha };
}

export const SUPPORTED_SOURCE_PAIR = Object.freeze({
  web: '39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba',
  backend: '24704e11143a0bf919559a41324603f936ae03cf',
});
function sourceEntry(record, component) {
  const entry = record?.repositories?.[component];
  const repository = component === 'web' ? 'UltiStatsDev/ultistats-web-ui' : 'UltiStatsDev/ultistats-backend';
  if (!entry || entry.repository !== repository) throw new Error(`Wrong ${component} repository`);
  if (entry.tag !== `v${record.version}`) throw new Error(`Wrong ${component} version tag`);
  checkTag(entry.tag);
  return entry;
}
// Backend producer checks only its owned source/compatibility assertions.
// Web's canonical validateRecord + metadata verification are still mandatory.
export function verifyBackend(record, { backendRepo }) {
  if (!record || typeof record !== 'object') throw new Error('Missing record');
  if (record.schemaVersion !== 1) throw new Error('Unsupported record schema version');
  if (record.status !== 'finalized') throw new Error('Expected finalized record');
  const source = sourceEntry(record, 'backend');
  if (record.backendPin !== source.commit) throw new Error('Backend pin mismatch');
  const c = record.compatibility;
  if (c?.fromSchema !== 9 || c.toSchema !== 9 || c.appRollback !== true) {
    throw new Error('Only proven V9 compatibility is supported');
  }
  if (!Array.isArray(c.supportedSourcePairs) || c.supportedSourcePairs.length !== 1 ||
      c.supportedSourcePairs[0]?.web !== SUPPORTED_SOURCE_PAIR.web ||
      c.supportedSourcePairs[0]?.backend !== SUPPORTED_SOURCE_PAIR.backend) {
    throw new Error('Unsupported previous source pair; initial proven39/247 only');
  }
  readExactTag(backendRepo, source.tag, source.commit);
  return { commit: source.commit, tag: source.tag };
}
export function verifyPairSources(record, { webRepo, backendRepo }) {
  const backend = verifyBackend(record, { backendRepo });
  const web = sourceEntry(record, 'web');
  readExactTag(webRepo, web.tag, web.commit);
  const pin = git(webRepo, 'show', `${web.commit}:dev/backend/revision`);
  if (pin !== record.backendPin) throw new Error('Web application Backend pin mismatch');
  return { version: record.version, backend, compatibility: record.compatibility };
}

export function runCli(args, emit = line => process.stdout.write(`${line}\n`)) {
  try {
    if (args[0] !== 'verify' || args.length !== 7) throw new Error('Expected verify --record JSON --web-repo PATH --backend-repo PATH');
    const options = {};
    for (let i = 1; i < args.length; i += 2) {
      const key = args[i]; const value = args[i + 1];
      if (!['--record', '--web-repo', '--backend-repo'].includes(key) || options[key] || !value || value.startsWith('--')) {
        throw new Error('Invalid or duplicate verification option');
      }
      options[key] = value;
    }
    let record;
    try { record = JSON.parse(readFileSync(options['--record'], 'utf8')); }
    catch { throw new Error('Cannot read record JSON'); }
    const result = verifyPairSources(record, { webRepo: options['--web-repo'], backendRepo: options['--backend-repo'] });
    emit(JSON.stringify({ ok: true, ...result }));
    return 0;
  } catch (error) {
    emit(JSON.stringify({ ok: false, reason: error.message }));
    return 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = runCli(process.argv.slice(2));
}
