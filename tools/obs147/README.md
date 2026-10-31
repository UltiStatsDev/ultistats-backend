# Shared OBS terminal SSE recovery (#147 / #135)

Only the shared worker changes in production. Browser-native CONNECTING retries remain untouched. A terminal CLOSED source is detached and recreated with one subscriber-aware timer:1s,2s,4s,8s, capped15s; valid connected resets failure count. Last unsubscribe/lease expiry cancels retry. Identity guards ignore callbacks from replaced sources. No extra widget SSE, Event Log polling, API or migration change.

## Tests

Use the configured Node runtime; run outside a sandbox which suppresses child test execution:

```bash
node --test src/test/js/*.test.mjs
```

Worker tests cover terminal/initial failure, CONNECTING ownership, bounded delays/reset, no subscriber bypass, stale callbacks, single active source, unsubscribe/expiry cleanup and synchronous constructor failure. Baseline36 named tests; fixed40.

Full JVM build uses JDK21, existing cache, `flock /tmp/ultistats-backend-gradle.lock ./gradlew build --no-daemon --max-workers=2`. There is no DB migration/API/schema delta requiring unrelated raw export suites. Do not publish generated raw archives; local SHA evidence is sufficient.

## Actual backend/proxy browser regression

Dedicated synthetic API19856, PG19456, proxy19857. Own DB/uploads only. Never point this harness at human or historical QA targets. Reserve/check ports before startup. Docker operations use bounded exclusive `/tmp/ultistats-browser-network.lock`; browser batches use bounded shared lock for the batch only. No idle lifetime leases.

`fault-proxy.cjs` forwards successful responses from real19856 unchanged. It injects only terminal HTTP503, active SSE disconnect, or one marked pending scoreboard GET. `/_test/state` exposes stream attempts/active/peak; PUT `/_test/fault` sets `{mode:'503'|'healthy',drop:true,hangNext:true}`. Only a GET marked `x-obs147-hang: once` hangs, allowing the browser test to identify the exact measured consumer. The proxy must remain loopback/test-only; these controls are not product endpoints.

```bash
PLAYWRIGHT_MODULE=<absolute-existing-playwright-module> \
OBS147_OUTPUT=/tmp/obs147-runtime/browser \
flock -s /tmp/ultistats-browser-network.lock node tools/obs147/check.cjs
```

The script starts/stops its own19857 proxy and browser, creates NEW synthetic teams/matches and restores global OBS config. Four scoreboards on two matches, Event Log and admin/preview share one stream. Both established200→disconnect→503→restore and initial503 must reconnect without reload. The check verifies a real stream200 and worker readyStateOPEN before accepting a fresh GOAL, then captures the actual matching event-log-changed frame (matchId/eventId/CREATED) from that same EventSource via a test-only listener, with no additional SSE; Event Log visibility, canonical scoreboard, per-match profile routing and global config synchronization must follow. A tagged GET for the measured source is then held to its normal1500ms abort with the SSE still OPEN; accepted score must appear within2s. Last subscriber cleanup is checked while another retry is pending. Trace, frame screenshots and JSON remain local in OBS147_OUTPUT.

`OBS147_SCENARIO=initial` runs only initial503 for baseline RED. Baseline8ec/ea3 leaves CLOSED2 with trace[200,503] or[503] and no further stream GET after restore; this is transport evidence, not scoreboard polling. Runtime errors/success values are never fabricated.

For native QA, the proxy can run persistently with `node tools/obs147/fault-proxy.cjs`, without holding an idle shared lock. Use source/admin URLs on proxy19857 for faults; real mutations go to synthetic API19856. After technical merge/availability the lead coordinates targeted native OBS retest. Engineering Chromium evidence is not native OBS acceptance. Issues147/135 stay open until QA+PO; Root/Web is sole checked merge actor after permanent review.

## Immutable handoff

Pin exact source HEAD, full JAR SHA256, packaged/HTTP OBS asset hashes and delta versus8ec and ea3. Expected production OBS asset delta is only `obs/shared-worker.js`; other inherited application changes versus ea3 belong to merged146 and must be identified separately. Preserve all old candidate artifacts and QA endstates. Use a separate147 manifest with new fixtures, runtime commands and explicit origin19856/proxy19857. No parallel Docker image build: final image goes through the assigned single builder via lead.
