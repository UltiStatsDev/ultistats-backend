import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const ci = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const cd = readFileSync(new URL('../../.github/workflows/cd.yml', import.meta.url), 'utf8');
test('candidate, stable, hotfix and immutable tags receive ordinary CI', () => {
  assert.match(ci, /pull_request:[\s\S]*?- candidate\/stable-v0\.1\.0/);
  assert.match(ci, /pull_request:[\s\S]*?- release/);
  assert.match(ci, /push:[\s\S]*?- candidate\/stable-v0\.1\.0/);
  assert.match(ci, /push:[\s\S]*?- hotfix\/\*\*/);
  assert.match(ci, /push:[\s\S]*?- agent\/hotfix\/\*\*/);
  assert.match(ci, /push:[\s\S]*?- agent\/feature\/160-stable-promotion/);
  assert.match(ci, /tags: \['v\*'\]/);
});
test('push branch validation uses actual ref and existing checks are retained', () => {
  assert.match(ci, /github.head_ref \|\| github.ref_name/);
  assert.match(ci, /node --test/);
  assert.match(ci, /\.\/gradlew build/);
  assert.match(ci, /node --test src\/test\/js\/\*\.test\.mjs/);
  for (const script of ['validate-compose.sh', 'deploy_test.sh', 'validate-workflow.sh']) assert.ok(ci.includes(`bash deploy/tests/${script}`));
});
test('publication paths cannot dispatch existing production deployment', () => {
  assert.match(cd, /on:\s*\n  push:\s*\n    branches: \[master\]/);
  const triggers = cd.slice(cd.indexOf('on:'), cd.indexOf('concurrency:'));
  for (const event of ['tags:', 'release:', 'workflow_run:', 'workflow_dispatch:', 'candidate/']) assert.ok(!triggers.includes(event));
  assert.ok(!ci.includes('workflow_dispatch'));
});
test('CI discovers nested build/helper boundary tests as well as promotion tests', () => {
  assert.match(ci, /find deploy\/stable -name '\*\.test\.mjs' -type f -print0/);
  assert.match(ci, /xargs -0 node --test/);
});
