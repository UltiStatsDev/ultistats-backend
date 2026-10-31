# OBS135 engineering checks and native QA handoff

Use only an isolated synthetic runtime. Backend origin owns admin and sources. No React change, no CD, no human API/DB/uploads/queue changes. Native OBS acceptance remains a separate QA/PO gate.

## Local checks

JDK21 and the existing Gradle cache are required. All JVM work must hold the shared build lock:

```bash
JAVA_HOME=/tmp/ultistats-backend-jdk21 \
GRADLE_USER_HOME=/tmp/ultistats-backend-gradle-cache \
PATH=/tmp/ultistats-backend-jdk21/bin:/usr/bin:/bin \
flock /tmp/ultistats-backend-gradle.lock ./gradlew build --no-daemon --max-workers=2
```

Run the real Node test runner outside a sandbox that suppresses child test execution. A result reporting only test filenames is insufficient; baseline26, candidate34 named tests are expected.

```bash
node --test src/test/js/*.test.mjs
bash deploy/tests/validate-compose.sh
bash deploy/tests/deploy_test.sh
bash deploy/tests/validate-workflow.sh
```

## Synthetic PostgreSQL and API

Current task ports: API19835, PostgreSQL19435; container `ultistats-obs135-pg`, runtime DB `obs135`, test DB `obs135_tests`. Never use human18011/18012/18081 or pins19432/19433/19434. Reserve ports with the lead and confirm availability before creating anything. All Docker build/run/exec/network/up/down/recreate batches use an **exclusive** `flock /tmp/ultistats-browser-network.lock` for their entire process. With `newgrp docker` (fish), put complex commands into a bash script and invoke one bash command. Do not hold idle locks.

Use PostgreSQL16 and a fresh DB with disposable credentials. Start the API with dedicated DATABASE_URL/USERNAME/PASSWORD and APP_STORAGE_ROOT. `run-api.sh` binds only the assigned19835 and runs foreground. The final QA runtime uses an exact SHA jar copied to an immutable directory with SHA256, and serves its packaged assets without development file overrides.

Opt-in real PostgreSQL tests reuse `application-postgres-list.yaml`; point **obs135_tests** at a separate DB, never runtime fixtures:

```bash
SPRING_PROFILES_ACTIVE=postgres-list \
TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:19435/obs135_tests \
TEST_POSTGRES_USERNAME=<synthetic-user> TEST_POSTGRES_PASSWORD=<synthetic-password> \
JAVA_HOME=/tmp/ultistats-backend-jdk21 \
GRADLE_USER_HOME=/tmp/ultistats-backend-gradle-cache \
PATH=/tmp/ultistats-backend-jdk21/bin:/usr/bin:/bin \
flock /tmp/ultistats-backend-gradle.lock ./gradlew test \
  --tests '*ObsScoreboardIntegrationTest' --tests '*ObsConfigIntegrationTest' \
  --no-daemon --max-workers=2
```

This proves9 real Flyway migrations through V8, current-team profile validation, first-save concurrency, independent persistence reads, no game/global mutations, and AFTER_COMMIT/rollback for profile/start signals. H2-only migration claims are insufficient.

## Real Chromium checks

Provide an existing Playwright module path via PLAYWRIGHT_MODULE when it is not in normal module resolution. Chromium defaults to `/usr/bin/chromium`; CHROMIUM_PATH may override. Each script creates NEW synthetic fixtures and exits, releasing browser/network locks. No browser libraries are added to the application.

```bash
PLAYWRIGHT_MODULE=<absolute-playwright-module> \
OBS135_BASE=http://127.0.0.1:19835 OBS135_OUTPUT=/tmp/obs135-runtime/browser \
flock -s /tmp/ultistats-browser-network.lock node tools/obs135/check.mjs

PLAYWRIGHT_MODULE=<absolute-playwright-module> \
OBS135_BASE=http://127.0.0.1:19835 OBS135_OUTPUT=/tmp/obs135-runtime/integration \
flock -s /tmp/ultistats-browser-network.lock node tools/obs135/integration.mjs
```

`check.mjs`: initial controls, immediate draft name/color/swap/reset preview, two same-match live sources, independent other match, save synchronization, unchanged team/match/EventLog, focus through background refresh, delayed match-selection race, screenshots.

`integration.mjs`: actual uploaded160×48 logo/contain, long Cyrillic, initials, auto/manual colors and contrast, six scoreboards(4same+2other)+EventLog+admin+two previews, exactly one worker and one OBS SSE, REST capacity and cleanup, PLANNED→live actual start, >60min including timeout/halftime events, accepted goal/update/undo/batch, unsent local object isolation, reload, offline keep/advance, finish during outage, reconnect≤2s, late final score correction with unchanged63:12, initial invalid/missing/unavailable transparency/admin reason, proportional500×80 screenshots. Worker state inspection uses Chromium CDP; it is instrumentation evidence, not native OBS evidence.

The scripts leave `result.json` and screenshots in OBS135_OUTPUT. SLA values are milliseconds measured after successful HTTP acceptance; all healthy event updates and network recovery must be ≤2000ms. Real network loss is induced only in the browser context, so the dedicated API can accept a finish while sources are offline.

## Native OBS QA fixtures

After an exact-SHA integration run:

```bash
node tools/obs135/fixtures.mjs create /tmp/obs135-runtime/integration/result.json /tmp/obs135-runtime/fixtures.json
node tools/obs135/fixtures.mjs point /tmp/obs135-runtime/fixtures.json
node tools/obs135/fixtures.mjs correct-finished /tmp/obs135-runtime/fixtures.json
```

`create` reuses the integration logo teams and creates an additional live match. Manifest contains stable live/planned/finished source links, admin and live EventLog. `point` appends a valid new point only to that manifest's live match. `correct-finished` reattributes the last final GOAL between teams while retaining the original endedAt. After QA, read actual canonical GET state; fixtures are no longer empty and final scores may differ from initial labels.

Verify profile persistence by capturing GET, stopping **only this API**, restarting the same immutable jar/DB/uploads and comparing profile, actual timestamps, scores and logo asset. Keep old pins/artifacts untouched. Include exact commit, jar SHA256, packaged asset hashes, DB container/image, runtime commands, fixture manifest and fresh endpoint availability in the handoff to the lead/permanent reviewer/native OBS QA. Issue135 stays open until QA and PO accept.
