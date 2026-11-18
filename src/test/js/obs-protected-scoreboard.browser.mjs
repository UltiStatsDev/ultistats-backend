// Owned synthetic HTTPS/PostgreSQL engineering check; JSON fixture input stays in memory, never trace/HAR.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
const input = JSON.parse(readFileSync(0, 'utf8'));
const { chromium } = await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const { origin, exactUrl, autoUrl, pg, match, other, team_a, team_b, evidence, spki } = input;
const sql = (text) =>
  execFileSync('docker', ['exec', '-i', pg, 'psql', '-XqAt', '-v', 'ON_ERROR_STOP=1', '-U', 'obs_fixture', '-d', 'obs_fixture'], {
    input: text,
    encoding: 'utf8',
  });
const browser = await chromium.launch({
  executablePath: '/usr/bin/chromium',
  headless: true,
  args: [`--ignore-certificate-errors-spki-list=${spki}`],
});
const results = [],
  requests = [];
try {
  const context = await browser.newContext({ viewport: { width: 1000, height: 180 } });
  await context.addCookies([{ name: 'fixture-cookie', value: 'must-not-reach-viewer-api', url: origin, secure: true }]);
  const page = await context.newPage();
  let errors = 0;
  page.on('pageerror', () => errors++);
  const network = await context.newCDPSession(page);
  await network.send('Network.enable');
  const wireRequests = new Map(),
    wireHeaders = new Map(),
    wireResponses = new Map(),
    wireFailures = new Set();
  network.on('Network.requestWillBeSent', ({ requestId, request }) => {
    const url = new URL(request.url);
    assert.equal(url.hash, '');
    assert.ok(!request.url.includes('key='));
    wireRequests.set(requestId, { path: url.pathname, headers: request.headers });
  });
  network.on('Network.requestWillBeSentExtraInfo', ({ requestId, headers }) => wireHeaders.set(requestId, headers));
  network.on('Network.responseReceived', ({ requestId, hasExtraInfo }) => wireResponses.set(requestId, { hasExtraInfo }));
  network.on('Network.loadingFailed', ({ requestId }) => wireFailures.add(requestId));
  const captureRequests = () => {
    requests.length = 0;
    for (const [requestId, request] of wireRequests) {
      const headers = Object.fromEntries(
        Object.entries(wireHeaders.get(requestId) ?? request.headers).map(([k, v]) => [k.toLowerCase(), v]),
      );
      const protectedRead = request.path.startsWith('/api/');
      if (!wireHeaders.has(requestId) && !wireResponses.has(requestId) && wireFailures.has(requestId)) {
        requests.push({ path: request.path, protected: protectedRead, transport: 'canceled-before-wire-headers' });
        continue;
      }
      if (protectedRead) {
        assert.ok(wireHeaders.has(requestId), 'real data requests require full wire header evidence');
        if (wireResponses.has(requestId)) assert.equal(wireResponses.get(requestId).hasExtraInfo, true);
        assert.ok(request.path.startsWith('/api/v1/obs/sources/scoreboard/'));
        assert.equal(headers.cookie, undefined);
        assert.equal(headers.referer, undefined);
        assert.ok([exactUrl, autoUrl].some((link) => headers.authorization === `Bearer ${new URL(link).hash.slice(5)}`));
      } else {
        assert.equal(headers.authorization, undefined);
        if (request.path.startsWith('/uploads/')) {
          assert.ok(wireHeaders.has(requestId), 'completed photos require full wire headers');
          assert.equal(headers.cookie, undefined);
        }
      }
      requests.push({
        path: request.path,
        protected: protectedRead,
        cookiePresent: !!headers.cookie,
        bearerPresent: !!headers.authorization,
      });
    }
  };
  const score = () => page.locator('.scoreboard-score').textContent();
  const waitScore = (expected) => page.waitForFunction((v) => document.querySelector('.scoreboard-score')?.textContent === v, expected);
  await page.goto(exactUrl);
  await waitScore('0 : 0');
  assert.equal(await page.locator('.scoreboard-clock').textContent(), '00:00');
  results.push('EXACT planned renders current shell and persisted teams');
  const invalid = await context.newPage();
  let invalidApiCalls = 0;
  invalid.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/')) invalidApiCalls++;
  });
  await invalid.goto(`${origin}/obs-sources/scoreboard.html?matchId=${match}`);
  await invalid.waitForFunction(() => document.querySelector('#status').textContent === 'Некорректная ссылка табло');
  assert.equal(invalidApiCalls, 0);
  await invalid.close();
  await page.bringToFront();
  results.push('missing key gives bounded visible feedback without data requests');

  sql(
    `UPDATE matches SET started_at=now()-interval '62 seconds' WHERE id='${match}'; UPDATE match_teams SET score=4 WHERE match_id='${match}' AND team_id='${team_a}';`,
  );
  await waitScore('4 : 0');
  assert.notEqual(await page.locator('.scoreboard-clock').textContent(), '00:00');
  sql(`UPDATE matches SET ended_at=started_at+interval '60 seconds' WHERE id='${match}';`);
  await page.waitForFunction(() => document.querySelector('.scoreboard-clock')?.textContent === '01:00');
  sql(`UPDATE match_teams SET score=3 WHERE match_id='${match}' AND team_id='${team_a}';`);
  await waitScore('3 : 0');
  await page.waitForTimeout(1100);
  assert.equal(await page.locator('.scoreboard-clock').textContent(), '01:00');
  results.push('real start/finish clock and post-finish score correction');
  await page.reload();
  await waitScore('3 : 0');
  assert.equal(new URL(page.url()).hash, new URL(exactUrl).hash);
  results.push('reload retains working fragment link');
  await page.goto(autoUrl);
  await waitScore('3 : 0');
  sql(
    `INSERT INTO obs_scoreboard_profiles(match_id,payload) VALUES ('${match}','{"swapped":true,"shortNames":{"${team_a}":"SHORT"},"teamColors":{"${team_a}":"#ff0000"}}');`,
  );
  await waitScore('0 : 3');
  assert.ok((await page.locator('#scoreboard').textContent()).includes('SHORT'));
  sql(
    `UPDATE matches SET started_at=now() WHERE id='${other}'; UPDATE match_teams SET score=8 WHERE match_id='${other}' AND team_id='${team_a}';`,
  );
  await waitScore('8 : 0');
  assert.ok(!(await page.locator('#scoreboard').textContent()).includes('SHORT'));
  assert.equal(await page.locator('.scoreboard-team.left').getAttribute('data-team-id'), team_a);
  results.push('AUTO changes identity and resets profile/order/colors/clock');
  sql(`UPDATE matches SET started_at=NULL,ended_at=NULL;`);
  await page.waitForFunction(
    () => document.querySelector('#scoreboard').hidden && document.querySelector('#status').textContent === 'Нет матча для трансляции',
  );
  results.push('AUTO empty selection clears prior display');
  sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`);
  await waitScore('0 : 3');
  let offline = true;
  await page.route('**/api/v1/obs/sources/scoreboard/auto', (route) => (offline ? route.abort() : route.continue()));
  await page.waitForFunction(() => document.querySelector('#status').textContent.includes('переподключение'));
  assert.equal(await score(), '0 : 3');
  offline = false;
  await page.waitForFunction(() => document.querySelector('#status').textContent === '');
  await page.unroute('**/api/v1/obs/sources/scoreboard/auto');
  results.push('network loss retains confirmed score and serialized polling resumes');
  await page.waitForFunction(
    () => document.querySelectorAll('.scoreboard-logo img').length === 2 && [...document.images].every((image) => image.complete),
  );
  const settledFrames = () =>
    page.evaluate(async () => {
      await document.fonts.ready;
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
  await settledFrames();
  const renderEvidence = await page.evaluate(() =>
    ['.scoreboard-score', '.scoreboard-clock'].map((selector) => {
      const element = document.querySelector(selector),
        style = getComputedStyle(element),
        box = element.getBoundingClientRect();
      return {
        selector,
        text: element.textContent,
        color: style.color,
        visibility: style.visibility,
        display: style.display,
        opacity: style.opacity,
        font: style.font,
        box: { x: box.x, y: box.y, width: box.width, height: box.height },
      };
    }),
  );
  assert.deepEqual(
    renderEvidence.map((element) => element.text),
    ['0 : 3', await page.locator('.scoreboard-clock').textContent()],
  );
  for (const element of renderEvidence) {
    assert.equal(element.visibility, 'visible');
    assert.equal(element.opacity, '1');
    assert.ok(element.box.width > 0 && element.box.height > 0);
  }
  for (let index = 0; index < 3; index++) {
    await settledFrames();
    const path = `${evidence}/settled-scoreboard-${index}.png`;
    await page.locator('#scoreboard').screenshot({ path, omitBackground: true, timeout: 10000 });
    const whitePixels = Number(
      execFileSync(
        'python3',
        [
          '-c',
          'from PIL import Image; import sys; im=Image.open(sys.argv[1]).convert("RGBA"); print(sum(1 for r,g,b,a in im.crop((410,0,590,134)).getdata() if a>200 and min(r,g,b)>230))',
          path,
        ],
        { encoding: 'utf8' },
      ),
    );
    assert.ok(whitePixels > 100, `settled central score/clock paint missing in capture ${index}`);
  }
  writeFileSync(`${evidence}/settled-render.json`, JSON.stringify(renderEvidence, null, 2) + '\n');
  sql(`UPDATE obs_source_keys SET revoked_at=now() WHERE scope='AUTO_SCOREBOARD';`);
  await page.waitForFunction(
    () => document.querySelector('#scoreboard').hidden && document.querySelector('#status').textContent === 'Ссылка недоступна',
  );
  captureRequests();
  const count = requests.filter((r) => r.protected).length;
  await page.waitForTimeout(2200);
  captureRequests();
  assert.equal(requests.filter((r) => r.protected).length, count);
  results.push('real revoke clears display and ends polling');
  assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
  assert.equal(await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.length)), 0);
  await network.detach();
  assert.equal(errors, 0);
  await page.close();
  // Deterministic clock/network regression uses the served production module and renderer.
  const fake = await context.newPage();
  await fake.addInitScript(() => {
    const nativeDecode = Image.prototype.decode;
    window.photoDecodes = [];
    Image.prototype.decode = function () {
      return nativeDecode.call(this).then(() => new Promise((resolve) => window.photoDecodes.push(resolve)));
    };
  });
  await fake.clock.install();
  let selected = match,
    pending = [];
  const snapshot = (id) => ({
    matchId: id,
    status: 'IN_PROGRESS',
    startedAt: '2026-01-01T00:00:00Z',
    endedAt: null,
    serverNow: '2026-01-01T00:01:00Z',
    teams: [
      { teamId: team_a, teamName: id === match ? 'A' : 'B', score: id === match ? 1 : 9, photoUrl: '/uploads/delayed.svg' },
      { teamId: team_b, teamName: 'Right', score: 2, photoUrl: null },
    ],
    profile: { swapped: false, shortNames: {}, teamColors: {} },
  });
  await fake.route('**/api/v1/obs/sources/scoreboard/auto', (r) => r.fulfill({ json: { snapshot: snapshot(selected) } }));
  await fake.route('**/uploads/delayed.svg', (r) => pending.push(r));
  await fake.goto(autoUrl);
  await fake.waitForFunction(() => document.querySelector('.scoreboard-score')?.textContent === '1 : 2');
  const finishPhoto = async (index, color) => {
    await fake.waitForFunction(() => true);
    await pending[index].fulfill({
      contentType: 'image/svg+xml',
      body: `<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><path fill="${color}" d="M0 0h10v10H0z"/></svg>`,
    });
    await fake.waitForFunction((count) => window.photoDecodes.length === count, index + 1);
  };
  await finishPhoto(0, '#ff0000');
  selected = other;
  await fake.clock.runFor(1000);
  await fake.waitForFunction(() => document.querySelector('.scoreboard-score')?.textContent === '9 : 2');
  await finishPhoto(1, '#ff0000');
  selected = match;
  await fake.clock.runFor(1000);
  await fake.waitForFunction(() => document.querySelector('.scoreboard-score')?.textContent === '1 : 2');
  await finishPhoto(2, '#00ff00');
  await fake.evaluate(() => {
    window.photoDecodes[2]();
  });
  await fake.waitForFunction(() => getComputedStyle(document.querySelector('.scoreboard-team.left')).backgroundColor === 'rgb(0, 255, 0)');
  await fake.evaluate(() => {
    window.photoDecodes[1]();
    window.photoDecodes[0]();
  });
  await fake.waitForFunction(() => getComputedStyle(document.querySelector('.scoreboard-team.left')).backgroundColor === 'rgb(0, 255, 0)');
  assert.equal(await fake.locator('.scoreboard-score').textContent(), '1 : 2');
  results.push('fake-clock AUTO A→B→A rejects obsolete image work, current photo accent wins');
  let late;
  await fake.unroute('**/api/v1/obs/sources/scoreboard/auto');
  await fake.route('**/api/v1/obs/sources/scoreboard/auto', (r) => {
    late = r;
  });
  await fake.clock.runFor(1000);
  await fake.waitForTimeout(1);
  await fake.evaluate(() => dispatchEvent(new Event('pagehide')));
  if (late) await late.fulfill({ json: { snapshot: snapshot(other) } }).catch(() => {});
  await fake.clock.runFor(5000);
  assert.equal(await fake.locator('#scoreboard').getAttribute('hidden'), '');
  results.push('pagehide aborts request, clears timers and ignores late response');
  await fake.close();
  await context.close();
  writeFileSync(
    `${evidence}/browser-results.json`,
    JSON.stringify(
      {
        results,
        requests,
        limits:
          'Engineering Chromium, owned loopback HTTPS/PostgreSQL; temporary certificate SPKI pin. Native OBS/CEF and product visual acceptance deferred.',
      },
      null,
      2,
    ) + '\n',
  );
  console.log(`PASS ${results.length} browser scenarios, ${requests.length} sanitized requests`);
} finally {
  await browser.close();
}
