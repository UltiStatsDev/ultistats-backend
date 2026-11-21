// Real isolated PostgreSQL/Caddy HTTPS with existing frozen Web helper; no trace/HAR.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
const input = JSON.parse(readFileSync(0, 'utf8'));
const { origin, pg, match, other, team_a, team_b, evidence, spki } = input;
const { chromium } = await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const sql = (text) => execFileSync('docker', ['exec', '-i', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'obs_fixture', '-d', 'obs_fixture'], { input: text, encoding: 'utf8' });
const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: [`--ignore-certificate-errors-spki-list=${spki}`] });
const results = [], wire = [];
const cookies = (values) => Object.entries(values).map(([name, value]) => ({ name, value, url: origin, secure: true }));
try {
  const context = await browser.newContext({ viewport: { width: 1000, height: 300 } });
  await context.addCookies(cookies(input.cookies));
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => { errors.push(error.message); console.log('pageerror', error.message); });
  context.on('request', async (request) => {
    const url = new URL(request.url());
    assert.equal(url.hash, ''); assert.ok(!request.url().includes('key='));
    let headers;
    try { headers = await request.allHeaders(); } catch {
      wire.push({ path: url.pathname, transport: 'canceled-before-wire-headers' }); return;
    }
    if (url.pathname.startsWith('/api/v1/obs/previews/')) {
      assert.ok(headers.cookie); assert.equal(headers.authorization, undefined);
      assert.ok(headers['x-ultistats-session-id']);
      wire.push({ path: url.pathname, cookiePresent: true, bearerPresent: false, capturedIdentityPresent: true });
    }
    if (url.pathname.startsWith('/uploads/')) { assert.equal(headers.cookie, undefined); assert.equal(headers.authorization, undefined); wire.push({ path: url.pathname, cookiePresent: false, bearerPresent: false }); }
  });
  const preview = (kind, mode = `matchId=${match}`) => `${origin}/obs-preview/${kind}.html?${mode}`;
  const settled = (target = page) => target.waitForResponse((r) => r.url().includes('/api/v1/obs/previews/') && r.status() === 200);
  const score = (expected, target = page) => target.waitForFunction((v) => document.querySelector('.scoreboard-score')?.textContent === v, expected);
  for (const kind of ['scoreboard', 'event-log']) for (const mode of [`matchId=${match}`, 'mode=auto']) {
    const response = settled(); await page.goto(preview(kind, mode)); await response;
    assert.equal(new URL(page.url()).hash, '');
    assert.equal(await page.locator('#status').textContent(), mode === 'mode=auto' ? 'Нет матча для трансляции' : '');
    if (kind === 'scoreboard' && mode !== 'mode=auto') await score('0 : 0');
    results.push(`standalone ${kind} ${mode === 'mode=auto' ? 'AUTO null' : 'EXACT'} via genuine helper`);
  }
  await page.goto(preview('scoreboard'));
  await score('0 : 0');
  // Same-origin parent uses real session cookie; each iframe owns its helper capture.
  for (const kind of ['scoreboard', 'event-log']) for (const mode of [`matchId=${match}`, 'mode=auto']) {
    const response = page.waitForResponse((r) => r.frame() !== page.mainFrame() && r.url().includes('/api/v1/obs/previews/') && r.status() === 200);
    await page.evaluate((src) => { document.querySelector('iframe')?.remove(); const frame = document.createElement('iframe'); frame.src = src; document.body.append(frame); }, preview(kind, mode));
    await response;
    const frame = page.frames().at(-1);
    await frame.waitForSelector('#status', { state: 'attached' });
    results.push(`same-origin iframe ${kind} ${mode === 'mode=auto' ? 'AUTO' : 'EXACT'} keyless`);
  }
  await page.goto(preview('scoreboard', 'mode=auto'));
  sql(`UPDATE matches SET started_at=now()-interval '62 seconds' WHERE id='${match}'; UPDATE match_teams SET score=4 WHERE match_id='${match}' AND team_id='${team_a}';`);
  await score('4 : 0');
  const pause = randomUUID();
  sql(`INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,team_id) VALUES ('${pause}','${match}',1,'TIMEOUT_START',now(),'${team_a}');`);
  await page.waitForFunction(() => document.querySelector('.scoreboard-notice')?.textContent.includes('Таймаут'));
  sql(`UPDATE matches SET ended_at=started_at+interval '60 seconds' WHERE id='${match}';`);
  await page.waitForFunction(() => document.querySelector('.scoreboard-notice')?.textContent === 'Матч завершён');
  assert.equal(await page.locator('.scoreboard-clock').textContent(), '01:00');
  sql(`UPDATE matches SET started_at=now() WHERE id='${other}'; UPDATE match_teams SET score=7 WHERE match_id='${other}' AND team_id='${team_a}';`);
  await score('7 : 0');
  sql('UPDATE matches SET started_at=NULL,ended_at=NULL;');
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden && document.querySelector('#status').textContent === 'Нет матча для трансляции');
  results.push('AUTO null→match→different match→null; confirmed timeout and finish clock reused');
  const a = randomUUID(), b = randomUUID(), event = randomUUID();
  sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES ('${other}','${a}','${team_a}','PLAYER','Иван','Исторический',7),('${other}','${b}','${team_b}','PLAYER','Петр','Защитник',8);
    UPDATE matches SET started_at=now() WHERE id='${other}';
    UPDATE obs_config SET payload='${JSON.stringify({ categories: ['GOAL'], maxRows: 1, ttlSeconds: 6, corner: 'TOP_RIGHT', backgroundColor: '#17202e', textColor: '#ffffff', teamColors: {} })}';`);
  await page.goto(preview('event-log', `matchId=${other}`)); await settled();
  sql(`INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,from_participant_id,to_participant_id) VALUES ('${event}','${other}',1,'GOAL',now(),'${a}','${b}'); UPDATE matches SET event_log_revision=event_log_revision+1 WHERE id='${other}';`);
  await page.waitForSelector('.feed-row[data-kind=original]');
  await page.waitForTimeout(1500);
  const deletion = await context.request.delete(`${origin}/api/v1/matches/${other}/events/${event}`, { headers: input.staff });
  assert.equal(deletion.status(), 204);
  await page.waitForSelector('.feed-row[data-kind=cancellation]');
  assert.equal(await page.locator('.feed-row:not(.leaving)').count(), 2);
  assert.equal(await page.locator('.cancel-label').textContent(), 'Отмена: Гол');
  await page.waitForFunction(() => document.querySelectorAll('.feed-row:not(.leaving)').length === 1);
  assert.equal(await page.locator('.feed-row:not(.leaving)').getAttribute('data-kind'), 'cancellation');
  results.push('real cancellation with maxRows1 preserves original and independent deadlines');
  await page.waitForFunction(() => document.querySelectorAll('.feed-row').length === 1);
  await page.screenshot({ path: `${evidence}/staff-event-log.png`, omitBackground: true });
  await page.evaluate(() => dispatchEvent(new Event('pagehide')));
  assert.equal(await page.locator('.feed-row:not(.leaving)').count(), 0);
  const stoppedAt = wire.length; await page.waitForTimeout(1200); assert.equal(wire.length, stoppedAt);
  results.push('staff pagehide clears rows and ends polling/helper subscription');
  sql('UPDATE matches SET started_at=NULL,ended_at=NULL;');
  await page.goto(preview('event-log', 'mode=auto')); await settled();
  assert.equal(await page.locator('#status').textContent(), 'Нет матча для трансляции');
  sql(`UPDATE matches SET started_at=now() WHERE id='${other}';`);
  let selected = await settled(); assert.equal((await selected.json()).snapshot.matchId, other);
  sql(`UPDATE matches SET started_at=now()+interval '1 second' WHERE id='${match}';`);
  selected = await settled(); assert.equal((await selected.json()).snapshot.matchId, match);
  assert.equal(await page.locator('.feed-row:not(.leaving)').count(), 0);
  results.push('Event Log AUTO null→match→different match clears prior rows');
  await page.goto(preview('scoreboard')); await score('4 : 0');
  let release, held;
  const arrived = new Promise((r) => { held = r; });
  await page.route('**/api/v1/obs/previews/scoreboard/*', async (route) => {
    const response = await route.fetch(); held();
    await new Promise((r) => { release = r; });
    try { await route.fulfill({ response }); } catch {}
  }, { times: 1 });
  await arrived;
  await context.addCookies(cookies(input.replacement));
  await page.evaluate(() => { const channel = new BroadcastChannel('ultistats-auth-session-v1'); channel.postMessage({ type: 'session-changed' }); channel.close(); });
  // A notice must originate in another channel object so the helper receives it.
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden);
  release(); await page.waitForTimeout(1500);
  assert.equal(await page.locator('#scoreboard').isHidden(), true);
  assert.match(await page.locator('#status').textContent(), /Сессия изменилась/);
  results.push('real replacement cookie + existing notice invalidates capture while real snapshot pending; late result discarded');
  await context.addCookies(cookies(input.cookies));
  await page.goto(preview('scoreboard')); await score('4 : 0');
  const current = await (await context.request.get(`${origin}/api/v1/auth/session`)).json();
  const csrf = await (await context.request.get(`${origin}/api/v1/auth/csrf`)).json();
  const logout = await context.request.post(`${origin}/api/v1/auth/logout`, { headers: {
    'Origin': origin, 'X-CSRF-TOKEN': csrf.token,
    'X-UltiStats-Installation-Id': current.installationId,
    'X-UltiStats-Account-Id': current.account.id, 'X-UltiStats-Session-Id': current.sessionId,
  }, data: { expectedSessionId: current.sessionId } });
  assert.equal(logout.status(), 200);
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden);
  results.push('actual logout ends captured staff preview');
  const revokedContext = await browser.newContext(); await revokedContext.addCookies(cookies(input.replacement));
  const revokedPage = await revokedContext.newPage(); await revokedPage.goto(preview('scoreboard')); await score('4 : 0', revokedPage);
  sql('UPDATE auth_sessions SET revoked_at=now();');
  await revokedPage.waitForFunction(() => document.querySelector('#scoreboard').hidden);
  await revokedContext.close();
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden);
  const before = wire.length; await page.waitForTimeout(1500); assert.equal(wire.length, before);
  results.push('actual revoked session clears and terminally stops');
  assert.deepEqual(errors, []);
  await context.close();
  // Viewer regression uses a fresh context: real cookie must still be omitted from source requests.
  const guest = await browser.newContext();
  const viewerKey = new URL(input.viewer).hash.slice(5);
  for (const path of [`/api/v1/obs/previews/scoreboard/${match}`, '/obs-preview/scoreboard.html']) {
    const denied = await guest.request.get(origin + path, { headers: { Authorization: `Bearer ${viewerKey}` } });
    assert.equal(denied.status(), 401); assert.equal(denied.headers()['cache-control'], 'no-store');
  }
  await guest.close(); results.push('genuine valid viewer key alone cannot read staff preview API or HTML');
  const viewer = await browser.newContext(); await viewer.addCookies(cookies(input.cookies));
  const viewerPage = await viewer.newPage();
  viewerPage.on('request', async (request) => { if (request.url().includes('/api/v1/obs/sources/')) { const headers = await request.allHeaders(); assert.equal(headers.cookie, undefined); assert.ok(headers.authorization); } });
  await viewerPage.goto(input.viewer); await score('4 : 0', viewerPage);
  await viewerPage.evaluate(() => dispatchEvent(new Event('pagehide')));
  assert.equal(await viewerPage.locator('#scoreboard').isHidden(), true);
  await viewer.close(); results.push('default viewer bearer remains credentialless; pagehide clears');
  assert.ok(wire.some((r) => r.path.startsWith('/uploads/') && r.cookiePresent === false));
  writeFileSync(`${evidence}/browser-results.json`, JSON.stringify({ results, wire, limits: 'Synthetic loopback engineering integration; frozen Web helper; per-process certificate SPKI trust only. Native OBS/CEF/ProductQA/hosted delivery not run.' }, null, 2) + '\n');
  console.log(`PASS ${results.length} real preview integration scenarios`);
} finally { await browser.close(); }
