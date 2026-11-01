import { execFileSync } from 'node:child_process';

const SHA = /^[0-9a-f]{40}$/;
const TAG = /^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
function git(repository, ...args) {
  try {
    return execFileSync('git', ['--no-replace-objects', '-C', repository, ...args], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 15000,
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
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
