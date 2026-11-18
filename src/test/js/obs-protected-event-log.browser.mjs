// Owned synthetic HTTPS/PostgreSQL engineering check; JSON fixture input stays in memory, never trace/HAR.
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
const input = JSON.parse(readFileSync(0, "utf8"));
const { chromium } = await import(pathToFileURL(process.env.OBS_PLAYWRIGHT).href);
const { origin, exactUrl, autoUrl, pg, match, other, team_a, team_b, evidence, spki } = input;
const sql = (text) =>
  execFileSync("docker", ["exec", "-i", pg, "psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-U", "obs_fixture", "-d", "obs_fixture"], {
    input: text,
    encoding: "utf8",
  });
const browser = await chromium.launch({
  executablePath: "/usr/bin/chromium",
  headless: true,
  args: [`--ignore-certificate-errors-spki-list=${spki}`],
});
const results = [],
  requests = [];
try {
  const context = await browser.newContext({ viewport: { width: 1000, height: 180 } });
  await context.addCookies([{ name: "fixture-cookie", value: "must-not-reach-viewer-api", url: origin, secure: true }]);
  const page = await context.newPage();
  let errors = 0;
  page.on("pageerror", () => errors++);
  const network = await context.newCDPSession(page);
  await network.send("Network.enable");
  const wireRequests = new Map(),
    wireHeaders = new Map(),
    wireResponses = new Map(),
    wireFailures = new Set();
  network.on("Network.requestWillBeSent", ({ requestId, request }) => {
    const url = new URL(request.url);
    assert.equal(url.hash, "");
    assert.ok(!request.url.includes("key="));
    wireRequests.set(requestId, { path: url.pathname, headers: request.headers });
  });
  network.on("Network.requestWillBeSentExtraInfo", ({ requestId, headers }) => wireHeaders.set(requestId, headers));
  network.on("Network.responseReceived", ({ requestId, hasExtraInfo }) => wireResponses.set(requestId, { hasExtraInfo }));
  network.on("Network.loadingFailed", ({ requestId }) => wireFailures.add(requestId));
  const captureRequests = () => {
    requests.length = 0;
    for (const [requestId, request] of wireRequests) {
      const headers = Object.fromEntries(
        Object.entries(wireHeaders.get(requestId) ?? request.headers).map(([k, v]) => [k.toLowerCase(), v]),
      );
      const protectedRead = request.path.startsWith("/api/");
      if (!wireHeaders.has(requestId) && !wireResponses.has(requestId) && wireFailures.has(requestId)) {
        requests.push({ path: request.path, protected: protectedRead, transport: "canceled-before-wire-headers" });
        continue;
      }
      if (protectedRead) {
        assert.ok(wireHeaders.has(requestId), "real data requests require full wire header evidence");
        if (wireResponses.has(requestId)) assert.equal(wireResponses.get(requestId).hasExtraInfo, true);
        assert.ok(request.path.startsWith("/api/v1/obs/sources/event-log/"));
        assert.equal(headers.cookie, undefined);
        assert.equal(headers.referer, undefined);
        assert.ok([exactUrl, autoUrl].some((link) => headers.authorization === `Bearer ${new URL(link).hash.slice(5)}`));
      } else {
        assert.equal(headers.authorization, undefined);
        if (request.path.startsWith("/uploads/")) {
          assert.ok(wireHeaders.has(requestId), "completed photos require full wire headers");
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
  const actorA = "00000000-0000-0000-0000-000000000234",
    actorB = "00000000-0000-0000-0000-000000000235",
    slot = "00000000-0000-0000-0000-000000000236",
    point = "00000000-0000-0000-0000-000000000237";
  sql(`INSERT INTO match_participants(match_id,participant_id,team_id,kind,first_name,last_name,number) VALUES
    ('${match}','${actorA}','${team_a}','PLAYER','Иван','Исторический',7),
    ('${match}','${actorB}','${team_b}','PLAYER','Петр','Защитник',8);
    INSERT INTO match_point_lineups(match_id,point_id,revision) VALUES ('${match}','${point}',1);
    INSERT INTO match_point_lineup_entries(match_id,point_id,team_id,position,slot_id,slot_number,resolved_participant_id)
    VALUES ('${match}','${point}','${team_b}',0,'${slot}',9,'${actorB}');`);
  const settings = (patch = {}) => {
    const config = {
      categories: ["GOAL", "BLOCK", "INTERCEPTION", "CALLAHAN"],
      maxRows: 2,
      ttlSeconds: 5,
      corner: "BOTTOM_LEFT",
      backgroundColor: "#17202e",
      textColor: "#ffffff",
      teamColors: { [team_a]: "#ff0000", [team_b]: "#00ff00" },
      ...patch,
    };
    sql(`UPDATE obs_config SET payload='${JSON.stringify(config)}';`);
  };
  const add = (n, type = "BLOCK_FIELD", temporary = false) => {
    const id = `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
    sql(`INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,from_participant_id,${temporary ? "to_slot_id" : "to_participant_id"})
      VALUES ('${id}','${match}',${n},'${type}',now(),'${actorA}','${temporary ? slot : actorB}');
      UPDATE matches SET event_log_revision=event_log_revision+1 WHERE id='${match}';`);
    return id;
  };
  const rows = () => page.locator(".feed-row:not(.leaving)");
  const waitRows = (n) => page.waitForFunction((n) => document.querySelectorAll(".feed-row:not(.leaving)").length === n, n);
  settings();
  add(1, "GOAL");
  await page.goto(exactUrl);
  await page.waitForResponse((r) => r.url().includes("/api/v1/obs/sources/event-log/") && r.status() === 200);
  assert.equal(await rows().count(), 0);
  results.push("generated EXACT served page establishes historical baseline");
  const second = add(2, "BLOCK_FIELD", true);
  await waitRows(1);
  assert.equal(await rows().first().locator(".left").textContent(), "7 Исторический И.");
  assert.equal(await rows().first().locator(".right").textContent(), "8 Защитник П.");
  assert.equal(await page.locator("#feed").getAttribute("data-corner"), "BOTTOM_LEFT");
  assert.equal(
    await rows()
      .first()
      .locator(".right")
      .evaluate((e) => e.style.getPropertyValue("--accent")),
    "#00ff00",
  );
  assert.equal(
    await rows()
      .first()
      .evaluate((e) => e.style.backgroundColor),
    "rgb(23, 32, 46)",
  );
  results.push("known historical attacker and resolved temporary defender keep canonical order and scoped colors/corner");
  sql(
    `UPDATE events SET event_type='INTERCEPTION' WHERE id='${second}'; UPDATE matches SET event_log_revision=event_log_revision+1 WHERE id='${match}';`,
  );
  await page.waitForFunction(() => document.querySelector(".feed-row svg")?.getAttribute("aria-label") === "Перехват");
  sql(`UPDATE match_point_lineup_entries SET resolved_participant_id=NULL WHERE slot_id='${slot}';`);
  await page.waitForFunction(() => document.querySelector(".feed-row .right")?.textContent === "Неизвестный игрок");
  assert.equal(
    await rows()
      .first()
      .locator(".right")
      .evaluate((e) => e.style.getPropertyValue("--accent")),
    "#00ff00",
  );
  results.push("current edit updates row and unresolved temporary actor retains its correct team");
  await page.screenshot({ path: `${evidence}/event-log.png`, omitBackground: true });
  await waitRows(0);
  await page.reload();
  await page.waitForResponse((r) => r.url().includes("/api/v1/obs/sources/event-log/") && r.status() === 200);
  assert.equal(await rows().count(), 0);
  results.push("ordinary TTL expires and reload does not replay historical events");
  settings({ ttlSeconds: 20 });
  add(3);
  add(4);
  add(5);
  await waitRows(2);
  assert.deepEqual(
    await rows().evaluateAll((nodes) => nodes.map((n) => n.dataset.eventId)),
    [5, 4].map((n) => `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`),
  );
  let offline = true;
  await page.route("**/api/v1/obs/sources/event-log/*", (r) => (offline ? r.abort() : r.continue()));
  await page.waitForTimeout(1300);
  assert.equal(await rows().count(), 2);
  offline = false;
  await page.waitForResponse((r) => r.url().includes("/api/v1/obs/sources/event-log/") && r.status() === 200);
  await page.unroute("**/api/v1/obs/sources/event-log/*");
  assert.equal(await rows().count(), 2);
  results.push("maxRows bound and transient network/reconnect retain confirmed rows without replay");
  settings({ categories: ["GOAL"], ttlSeconds: 20 });
  await waitRows(0);
  add(6, "GOAL");
  await waitRows(1);
  results.push("server category changes filter current rows and allow new selected categories");
  sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`);
  await page.goto(autoUrl);
  await page.waitForResponse((r) => r.url().includes("/event-log/auto") && r.status() === 200);
  assert.equal(await rows().count(), 0);
  add(7, "GOAL");
  await waitRows(1);
  sql(`UPDATE matches SET started_at=now() WHERE id='${other}';`);
  await waitRows(0);
  sql(`UPDATE matches SET started_at=NULL,ended_at=NULL;`);
  await page.waitForTimeout(1200);
  assert.equal(await page.locator("#feed").getAttribute("data-corner"), null);
  sql(`UPDATE matches SET started_at=now() WHERE id='${match}';`);
  await page.waitForTimeout(1200);
  assert.equal(await rows().count(), 0);
  add(8, "GOAL");
  await waitRows(1);
  results.push("AUTO A→B→null→A resets cards/config/baseline and accepts only new events");
  sql(`UPDATE obs_source_keys SET revoked_at=now() WHERE scope='AUTO_EVENT_LOG';`);
  await waitRows(0);
  await page.waitForTimeout(250);
  captureRequests();
  const count = requests.filter((r) => r.protected).length;
  await page.waitForTimeout(1300);
  captureRequests();
  assert.equal(requests.filter((r) => r.protected).length, count);
  await page.reload();
  await page.waitForResponse((r) => r.url().includes("/event-log/auto") && r.status() === 401);
  assert.equal(await rows().count(), 0);
  results.push("revocation clears immediately, ends polling, reload remains empty");
  await page.goto(exactUrl);
  await page.waitForResponse((r) => r.url().includes("/event-log/") && r.status() === 200);
  add(9, "GOAL");
  await waitRows(1);
  await page.evaluate(() => dispatchEvent(new Event("pagehide")));
  assert.equal(await rows().count(), 0);
  await page.waitForTimeout(250);
  captureRequests();
  const ended = requests.filter((r) => r.protected).length;
  await page.waitForTimeout(1300);
  captureRequests();
  assert.equal(requests.filter((r) => r.protected).length, ended);
  assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
  assert.equal(await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.length)), 0);
  assert.equal(errors, 0);
  results.push("pagehide clears renderer and all polling/timers; no storage, worker or public fallback");
  await network.detach();
  // Served module with deterministic clock/network: obsolete image decode and late data cannot repaint.
  const fake = await context.newPage();
  await fake.addInitScript(() => {
    const decode = Image.prototype.decode;
    window.decodes = [];
    Image.prototype.decode = function () {
      return decode.call(this).then(() => new Promise((resolve) => window.decodes.push(resolve)));
    };
  });
  await fake.clock.install();
  let selected = match,
    revision = 0,
    pending = [];
  const snapshot = () => ({
    matchId: selected,
    logRevision: revision,
    serverNow: "2026-01-01T00:00:00Z",
    teams: [
      { teamId: team_a, teamName: "A", score: 0, photoUrl: "/uploads/delayed.svg" },
      { teamId: team_b, teamName: "B", score: 0, photoUrl: "https://legacy.invalid/unavailable" },
    ],
    config: {
      categories: ["GOAL"],
      maxRows: 2,
      ttlSeconds: 8,
      corner: "TOP_RIGHT",
      backgroundColor: "#17202e",
      textColor: "#ffffff",
      teamColors: {},
    },
    participants: [
      {
        participantId: actorA,
        teamId: team_a,
        kind: "PLAYER",
        firstName: "Иван",
        lastName: "Игрок",
        number: 7,
        unknownSlot: null,
      },
    ],
    temporarySlots: [],
    retractions: [],
    events: revision
      ? [
          {
            id: actorB,
            sequenceNumber: 1,
            type: "GOAL",
            occurredAt: "2026-01-01T00:00:00Z",
            fromParticipantId: actorA,
            toParticipantId: actorA,
          },
        ]
      : [],
  });
  await fake.route("**/api/v1/obs/sources/event-log/auto", (r) => r.fulfill({ json: { snapshot: snapshot() } }));
  await fake.route("**/uploads/delayed.svg", (r) => pending.push(r));
  await fake.goto(autoUrl);
  const finishPhoto = async (index, color) => {
    for (let attempt = 0; pending.length <= index && attempt < 100; attempt++) await new Promise((r) => setTimeout(r, 10));
    assert.ok(pending[index]);
    await pending[index].fulfill({
      contentType: "image/svg+xml",
      body: `<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><path fill="${color}" d="M0 0h10v10H0z"/></svg>`,
    });
    await fake.waitForFunction((n) => window.decodes.length === n, index + 1);
  };
  await finishPhoto(0, "#ff0000");
  selected = other;
  await fake.clock.runFor(1000);
  await finishPhoto(1, "#ff0000");
  selected = match;
  await fake.clock.runFor(1000);
  await finishPhoto(2, "#00ff00");
  await fake.evaluate(() => window.decodes[2]());
  revision++;
  await fake.clock.runFor(1000);
  await fake.waitForFunction(() => document.querySelector(".feed-row .left")?.style.getPropertyValue("--accent") === "#00ff00");
  await fake.evaluate(() => {
    window.decodes[0]();
    window.decodes[1]();
  });
  assert.equal(await fake.locator(".feed-row .left").evaluate((e) => e.style.getPropertyValue("--accent")), "#00ff00");
  await fake.clock.runFor(4000);
  revision++;
  await fake.clock.runFor(1000);
  assert.equal(await fake.locator(".feed-row:not(.leaving)").count(), 1);
  await fake.clock.runFor(3100);
  assert.equal(await fake.locator(".feed-row:not(.leaving)").count(), 0);
  results.push("fake clock proves A→B→A photo generation guard and unchanged original row deadline");
  let late;
  await fake.unroute("**/api/v1/obs/sources/event-log/auto");
  await fake.route("**/api/v1/obs/sources/event-log/auto", (r) => {
    late = r;
  });
  await fake.clock.runFor(1000);
  await fake.evaluate(() => dispatchEvent(new Event("pagehide")));
  if (late) await late.fulfill({ json: { snapshot: snapshot() } }).catch(() => {});
  await fake.clock.runFor(5000);
  assert.equal(await fake.locator("#feed").textContent(), "");
  results.push("pagehide rejects late data and delayed photo results");
  await fake.close();
  await context.close();
  writeFileSync(
    `${evidence}/event-browser-results.json`,
    JSON.stringify(
      {
        results,
        requests,
        limits:
          "Owned synthetic PostgreSQL, Caddy HTTPS and Chromium; native OBS/CEF and product visual acceptance deferred. Safe same-origin optional photos preserve automatic accent; unavailable legacy photos fall back.",
      },
      null,
      2,
    ) + "\n",
  );
  console.log(`PASS ${results.length} Event Log browser scenarios, ${requests.length} sanitized requests`);
} finally {
  await browser.close();
}
