# Replay-safe undo verification

Run JVM commands with JDK21 and the shared Gradle lock:

```sh
JAVA_HOME=/tmp/ultistats-backend-jdk21 GRADLE_USER_HOME=/tmp/ultistats-backend-gradle-cache PATH=/tmp/ultistats-backend-jdk21/bin:/usr/bin:/bin flock /tmp/ultistats-backend-gradle.lock ./gradlew build --no-daemon --max-workers=2
```

Real PostgreSQL: provision an isolated database; set `SPRING_PROFILES_ACTIVE=postgres-list`,
`TEST_POSTGRES_URL`, `TEST_POSTGRES_USERNAME`, `TEST_POSTGRES_PASSWORD` and run
`test --tests '*ReplaySafeUndo*' --tests '*UndoLastEventControllerTest' --tests '*EventBatchControllerTest' --rerun-tasks`
with the same JDK/cache/lock/options. Flyway must be enabled, SQL-init disabled.
The test schema is for H2 only. V8 is independent scoreboard migration; final integration
must include it before V9, using a fresh task-owned DB for the ordered history.

Install pinned tool dependencies (`pnpm install --frozen-lockfile` here), then:

```sh
node tools/undo144/check.mjs build/contracts/undo144
```

The raw document and fixtures come from actual MockMvc HTTP, without schema
normalization. AJV validates success, replay, recovery, durable rejection, identity
conflict and400/404; required/null negative cases and strict generated TypeScript
consumer compilation also run.

For real native transport controls, start a verified immutable JAR with
`bash tools/undo144/run-api.sh /absolute/immutable.jar`. This helper uses synthetic
API19844/PG19444 only. Verify readiness separately. The script does not provision
or reset a DB and must never target user/QA/shared fixtures.

```sh
PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium \
PLAYWRIGHT_MODULE=/absolute/path/to/playwright \
UNDO144_OUT=/tmp/ultistats-undo144/browser-EXACTHEAD \
flock -s /tmp/ultistats-browser-network.lock timeout 120s node tools/undo144/browser.mjs
```

Defaults to free loopback19845 for the fault proxy and API19844. For an independent
review target set `UNDO144_BASE=http://127.0.0.1:OWN_API_PORT` and
`UNDO144_PROXY_PORT=OWN_PROXY_PORT`; the proxy upstream port is derived from BASE.
Requires Python3 and
Playwright+Chromium. Each run creates registered synthetic teams and two matches
using UNKNOWN participants. `owned.json` records exact fixture ownership; keep it
for recovery/review or delete only those IDs. No real data/uploads are used.

The proxy forwards each incoming request once. For the first successful upstream
undo it fully reads the real committed response, then either closes before any
headers, or sends real headers and truncates the real body. It never retries or
fabricates success. The page has one onclick/native fetch and no JS retry.

Assertions include warm GET/POST TCP reuse, headerless transport replay on a new
TCP connection, identical envelopes/results, exact canonical UUIDs/sequences,
one revision and one existing DELETED SSE. The afterheaders control has one POST,
fetch body failure and the same recoverable outcome. Raw logs, canonical snapshots,
receipt, SSE, browser version and screenshots are persisted; there are no suite retries.

Ordered upgrade proof after actual master synchronization: extract `BOOT-INF/lib/*`
from the verified JAR into a temporary directory, then run under the JVM lock:

```sh
java --class-path '/absolute/extracted/BOOT-INF/lib/*' tools/undo144/UpgradeProbe.java src/main/resources/db/migration
```

The probe refuses to run without V8 in the exact checkout. It creates a fresh
`undo144_upgrade_<random>` database only in this task's PostgreSQL19444, migrates
throughV7, seeds a synthetic UNKNOWN/PULL/match/revision/score, then appliesV8/V9
and verifies complete ordered history and unchanged data. Keep the reported own
DB/UUIDs for review. Compilation and the missing-V8 guard were checked before
integration; passing a complete upgrade requires the actual master migrations.
