import { execFileSync } from 'node:child_process';
import { realpathSync } from 'node:fs';

const BASELINE = '24704e11143a0bf919559a41324603f936ae03cf';
const PARITY_PATHS = ['src/main', 'src/test', 'build.gradle.kts', 'settings.gradle.kts', 'gradle', 'gradlew', 'Dockerfile'];
const git = (cwd, ...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 }).trim();

export async function inspectSource(sourceDir, expectedSha) {
  if (!/^[a-f0-9]{40}$/.test(expectedSha ?? '')) throw new Error('INVALID_SOURCE_SHA');
  if (realpathSync(sourceDir) !== realpathSync(git(sourceDir, 'rev-parse', '--show-toplevel'))) throw new Error('SOURCE_ROOT_MISMATCH');
  const sourceSha = git(sourceDir, 'rev-parse', 'HEAD');
  if (sourceSha !== expectedSha) throw new Error('SOURCE_SHA_MISMATCH');
  if (git(sourceDir, 'status', '--porcelain=v1', '--untracked-files=all', '--ignored=matching')) throw new Error('SOURCE_NOT_CLEAN');
  const productTrees = {};
  for (const path of PARITY_PATHS) {
    const actual = git(sourceDir, 'rev-parse', `${sourceSha}:${path}`);
    if (actual !== git(sourceDir, 'rev-parse', `${BASELINE}:${path}`)) throw new Error('BASELINE_PARITY_MISMATCH');
    productTrees[path] = actual;
  }
  return { sourceSha, baselineSha: BASELINE, productTrees };
}
