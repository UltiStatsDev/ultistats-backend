import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const ci = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8');
const cd = readFileSync(new URL('../../.github/workflows/cd.yml', import.meta.url), 'utf8');
const job = name => {
  const section = ci.split(`\n  ${name}:\n`)[1]?.split(/\n  [a-z][a-z0-9-]*:\n/)[0];
  assert.ok(section, `Missing ${name} job`); return section;
};
test('private stand candidate and author bootstrap receive actual CI', () => {
  const triggers = ci.split('\npermissions:')[0];
  assert.match(triggers, /pull_request:[\s\S]*?- candidate\/private-stand-v1/);
  assert.match(triggers, /push:[\s\S]*?- candidate\/private-stand-v1/);
  assert.match(triggers, /push:[\s\S]*?- agent\/chore\/168-stand-ci/);
  assert.match(ci, /\$BRANCH_NAME" == candidate\/private-stand-v1/);
});
test('stand unit job runs wiring regression plus all root and nested stand tests', () => {
  const unit = job('private-stand-node');
  assert.match(unit, /node-version: '22'/);
  assert.match(unit, /node --test deploy\/ci\/private-stand.test.mjs/);
  assert.match(unit, /find deploy\/stand -name '\*\.test\.mjs' -type f -print0/);
  assert.match(unit, /xargs -0 node --test/);
});
test('role module requires its real PostgreSQL runner and complete source history', () => {
  const role = job('private-stand-roles-pg');
  assert.match(role, /fetch-depth: 0/);
  assert.match(role, /\[\[ -f deploy\/stand\/sql-roles.mjs \]\]/);
  assert.match(role, /test -f deploy\/stand\/tests\/run-sql-roles.sh/);
  assert.match(role, /bash deploy\/stand\/tests\/run-sql-roles.sh/);
  assert.match(role, /steps.roles.outputs.present == 'true'/);
  assert.ok(!/H2|h2database|gradlew/.test(role));
});
test('private CI remains candidate scoped and preserves stable baseline parity checks', () => {
  for (const name of ['private-stand-node', 'private-stand-roles-pg']) {
    assert.match(job(name), /if:.*github.base_ref == 'candidate\/private-stand-v1'/);
    assert.match(job(name), /github.ref == 'refs\/heads\/candidate\/private-stand-v1'/);
  }
  assert.match(job('stable-promotion'), /fetch-depth: 0/);
  assert.match(job('stable-promotion'), /find deploy\/stable -name '\*\.test\.mjs'/);
  const events = cd.slice(cd.indexOf('on:'), cd.indexOf('concurrency:'));
  assert.match(events, /branches: \[master\]/);
  assert.ok(!events.includes('candidate/'));
});
test('fence module requires actual PostgreSQL proxy integration with owned Docker locks', () => {
  const proxy = job('private-stand-fence-pg');
  assert.match(proxy, /fetch-depth: 0/);
  assert.match(proxy, /\[\[ -f deploy\/stand\/sql-maintenance.mjs \]\]/);
  assert.match(proxy, /test -f deploy\/stand\/tests\/sql-proxy.integration.test.mjs/);
  assert.match(proxy, /docker build -t ultistats-stand170-proxy:test deploy\/stand\/sql-proxy/);
  assert.match(proxy, /STAND_SQL_INTEGRATION: '1'/);
  assert.match(proxy, /node --test deploy\/stand\/tests\/sql-proxy.integration.test.mjs/);
  assert.match(proxy, /flock -w 120 -x \/tmp\/ultistats-web-heavy-check.lock \\[\s\S]*?flock -w 120 -x \/tmp\/ultistats-browser-network.lock/);
  assert.ok(!/H2|h2database|gradlew/.test(proxy));
});
