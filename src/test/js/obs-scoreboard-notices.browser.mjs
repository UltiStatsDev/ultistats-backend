// Owned synthetic PostgreSQL/Caddy HTTPS check. No request routing or mocked snapshots.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
const { chromium } = await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const { origin, exactUrl, autoUrl, pg, match, other, team_a, evidence, spki } = JSON.parse(readFileSync(0, 'utf8'));
const sql = text => execFileSync('docker', ['exec', '-i', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'obs_fixture', '-d', 'obs_fixture'], { input: text, encoding: 'utf8' });
const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: [`--ignore-certificate-errors-spki-list=${spki}`] });
const results = [], wire = [], states = [], identities = [];
try {
  const context = await browser.newContext({ viewport: { width: 1000, height: 160 } });
  await context.addCookies([{ name: 'fixture-cookie', value: 'must-not-reach-data', url: origin, secure: true }]);
  const page = await context.newPage(), session = await context.newCDPSession(page);
  await session.send('Network.enable');
  const requests = new Map(); let errors = 0;
  page.on('pageerror', () => errors++);
  session.on('Network.requestWillBeSent', ({ requestId, request }) => requests.set(requestId, new URL(request.url).pathname));
  session.on('Network.requestWillBeSentExtraInfo', ({ requestId, headers }) => {
    const path = requests.get(requestId), h = Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
    if (!path?.startsWith('/api/')) return;
    assert.ok(path.startsWith('/api/v1/obs/sources/scoreboard/'));
    assert.equal(h.cookie, undefined); assert.equal(h.referer, undefined);
    assert.ok([exactUrl, autoUrl].some(link => h.authorization === `Bearer ${new URL(link).hash.slice(5)}`));
    wire.push({ path, cookie: false, referrer: false, bearer: true });
  });
  page.on('response', async response => {
    if (!new URL(response.url()).pathname.startsWith('/api/v1/obs/sources/scoreboard/') || response.status() !== 200) return;
    const payload = await response.json().catch(() => null), s = payload?.snapshot === undefined ? payload : payload.snapshot;
    states.push(s ? { matchId: s.matchId, status: s.status, pauses: s.activePauses.map(p => p.type) } : null);
  });
  const waitLabels = async expected => {
    try {
      await page.waitForFunction(labels => JSON.stringify([...document.querySelectorAll('.scoreboard-notice')].map(n => n.textContent)) === JSON.stringify(labels), expected);
    } catch (error) {
      console.log(JSON.stringify({ expected, states: states.slice(-3), dom: await page.locator('body').innerText() }));
      throw error;
    }
  };
  const labels = () => page.locator('.scoreboard-notice').allTextContents();
  const clear = () => sql(`DELETE FROM events WHERE match_id='${match}'; DELETE FROM match_announcements WHERE match_id='${match}';`);
  const eventPause = kind => {
    clear();
    const [participant, receiver] = sql(`SELECT participant_id FROM match_participants WHERE match_id='${match}' AND team_id='${team_a}' ORDER BY participant_id LIMIT 2`).trim().split('\n');
    const types = kind === 'HALFTIME' ? ['PULL', 'PICKUP', 'GOAL', 'HALFTIME_START'] : ['TIMEOUT_START'];
    types.forEach((type, index) => sql(`INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,team_id,from_participant_id,to_participant_id) VALUES ('${randomUUID()}','${match}',${index + 1},'${type}',now()-interval '${10 - index} seconds','${team_a}',${['PULL','PICKUP','GOAL'].includes(type) ? `'${participant}'` : 'NULL'},${type === 'GOAL' ? `'${receiver}'` : 'NULL'});`));
  };
  const announce = () => sql(`INSERT INTO match_announcements(match_id,id,started_second,started_nano) VALUES ('${match}','${randomUUID()}',extract(epoch from now())::bigint,0);`);
  sql(`UPDATE matches SET started_at=NULL,ended_at=NULL; DELETE FROM obs_scoreboard_profiles; UPDATE match_teams SET score=3 WHERE match_id='${match}' AND team_id='${team_a}'; UPDATE match_teams SET score=8 WHERE match_id='${other}' AND team_id='${team_a}';`);
  await page.goto(exactUrl);
  await page.waitForFunction(() => document.querySelector('.scoreboard-clock')?.textContent === '00:00');
  assert.deepEqual(await labels(), []); results.push('EXACT PLANNED 00:00 without notices');
  sql(`UPDATE matches SET started_at=now()-interval '120 seconds' WHERE id='${match}';`);
  for (const [kind, text] of [['TIMEOUT', 'Таймаут'], ['HALFTIME', 'Халфтайм']]) {
    eventPause(kind); await waitLabels([text]);
    announce(); await waitLabels([text, 'Объявление']);
    assert.equal(await page.locator('.scoreboard-score').textContent(), '3 : 0');
    await page.waitForTimeout(1200); assert.deepEqual(await labels(), [text, 'Объявление']);
    await page.reload(); await waitLabels([text, 'Объявление']);
    results.push(`${kind} and announcement real snapshots, repeat and fresh opening`);
  }
  for (const width of [1000, 360]) {
    await page.setViewportSize({ width, height: 160 });
    await page.waitForFunction(w => document.querySelector('#stage').style.transform === `scale(${w / 1000})`, width);
    await page.evaluate(async () => {
      await document.fonts.ready;
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    const boxes = await page.locator('.scoreboard-clock, .scoreboard-notice').evaluateAll(nodes => nodes.map(n => {
      const r = n.getBoundingClientRect(); return { text: n.textContent, x: r.x, y: r.y, right: r.right, bottom: r.bottom, width: r.width, height: r.height };
    }));
    for (const box of boxes) { assert.ok(box.width > 0 && box.height > 0); assert.ok(box.x >= 0 && box.right <= width && box.y >= 0 && box.bottom <= 160); }
    await page.screenshot({ path: `${evidence}/notices-${width}.png`, omitBackground: true });
    writeFileSync(`${evidence}/geometry-${width}.json`, JSON.stringify(boxes, null, 2));
  }
  results.push('wide/narrow timing and both notices remain inside transparent 1000x160 stage');
  await page.setViewportSize({ width: 1000, height: 160 });
  clear(); announce(); await waitLabels(['Объявление']);
  await context.setOffline(true); await page.waitForFunction(() => document.querySelector('#status').textContent.includes('переподключение'));
  assert.deepEqual(await labels(), ['Объявление']);
  eventPause('TIMEOUT'); await context.setOffline(false); await waitLabels(['Таймаут']);
  clear(); await waitLabels([]); results.push('announcement only, offline retains confirmed notice; reconnect correction and resume clear it');
  sql(`UPDATE matches SET ended_at=started_at+interval '90 seconds' WHERE id='${match}';`);
  await waitLabels(['Матч завершён']);
  await page.waitForFunction(() => document.querySelector('.scoreboard-clock').textContent === '01:30');
  await page.waitForTimeout(1200); assert.equal(await page.locator('.scoreboard-clock').textContent(), '01:30');
  sql(`UPDATE matches SET ended_at=started_at+interval '91 seconds' WHERE id='${match}';`);
  await page.waitForFunction(() => document.querySelector('.scoreboard-clock').textContent === '01:31');
  await page.reload(); await waitLabels(['Матч завершён']); results.push('FINISHED anchors endedAt, accepts late timing correction and fresh open');
  await page.goto(autoUrl); await waitLabels(['Матч завершён']);
  sql(`UPDATE matches SET started_at=now() WHERE id='${other}';`);
  await waitLabels([]); await page.waitForFunction(() => document.querySelector('.scoreboard-score')?.textContent === '8 : 0');
  sql('UPDATE matches SET started_at=NULL,ended_at=NULL;');
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden && document.querySelector('#status').textContent === 'Нет матча для трансляции');
  assert.deepEqual(await labels(), []); results.push('AUTO switch and null clear previous finished state');
  sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`); eventPause('TIMEOUT'); await waitLabels(['Таймаут']);
  sql("UPDATE obs_source_keys SET revoked_at=now() WHERE scope='AUTO_SCOREBOARD';");
  await page.waitForFunction(() => document.querySelector('#scoreboard').hidden && document.querySelector('#status').textContent === 'Ссылка недоступна');
  assert.deepEqual(await labels(), []); const count = wire.length;
  await page.waitForTimeout(2200); assert.equal(wire.length, count); results.push('terminal real revoke clears notices and ends polling');
  assert.ok(wire.length > 10); assert.equal(errors, 0);
  for (const asset of ['protected-scoreboard.mjs', 'scoreboard-renderer.mjs', 'scoreboard.css']) {
    const response = await page.evaluate(async path => {
      const result = await fetch(path, { credentials: 'omit', cache: 'no-store' });
      return { status: result.status, bytes: Array.from(new Uint8Array(await result.arrayBuffer())) };
    }, `/obs/${asset}`);
    assert.equal(response.status, 200);
    const served = createHash('sha256').update(Uint8Array.from(response.bytes)).digest('hex');
    const checked = createHash('sha256').update(readFileSync(`src/main/resources/static/obs/${asset}`)).digest('hex');
    assert.equal(served, checked); identities.push({ asset, sha256: served });
  }
  writeFileSync(`${evidence}/notices-browser-results.json`, JSON.stringify({ results, wire, states, identities, limits: 'Engineering Chromium; native OBS/CEF and ProductQA pending. No mocked wire.' }, null, 2));
  console.log(`PASS ${results.length} actual-wire notice scenarios; ${wire.length} protected wire requests`);
} finally { await browser.close(); }
