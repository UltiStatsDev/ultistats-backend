# OBS Scoreboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver issue135 persistent per-match scoreboard, backend admin preview and stable source through verified PR and immutable QA runtime.

**Architecture:** OBS-only snapshot/profile APIs read committed scores and actual persisted lifecycle times. Existing one-SSE SharedWorker routes additive invalidations to an independent source and isolated draft preview; existing Event Log remains compatible.

**Tech Stack:** Kotlin/Spring/JDBC/Flyway PostgreSQL; native JS modules/CSS; JUnit5/node:test/Playwright.

**Spec:** docs/superpowers/specs/2026-10-31-obs-scoreboard-design.md

## Global Constraints

- Source recommended 1000×160; transparent outside, read-only, stable per-match URL.
- Healthy score synchronization <=2 seconds after successful server save; clock updates every second and minutes exceed60.
- Existing actual startedAt/endedAt exclusively determine elapsed time, including timeout/halftime; finished duration survives corrections/reloads/reconnect.
- Unsaved/reset/swap draft affects preview only; persisted per-match profile survives restart; no team/match/EventLog/global config mutation.
- Preserve old event v1/v2/queue and terminal semantics, renderer141 glyphs; keep SharedWorker single OBS SSE.
- Backend origin only; React N/A. Synthetic data/ports only; human18011/18012/18081 and pins19432/19433/19434 untouched. JVM builds flock /tmp/ultistats-backend-gradle.lock, JDK21, max-workers2; browser shared / Docker exclusive bounded network lock.
- One PR Refs135 (no Closes/Fixes), no self merge; native OBS QA and PO gate remain explicit.

## Review Focus

- Delayed responses/images after admin changes match must never overwrite a newer draft (Task3 browser race).
- Server/browser wall-clock skew and hanging HTTP must not reset or stall known live clock (Task2 monotonic and timeout tests).
- FINISHED late event corrections must remain subscribed indefinitely (Task2/4 transport and browser).
- Concurrent profile saves/team change must not persist overrides for stale teams (Task1 locked membership validation, PG).
- Many sources plus EventLog and preview must leave REST connections available and release worker leases (Task2 worker/Task4 browser).

### Task 1: Persistent canonical scoreboard API and committed lifecycle invalidations

**Files:** Create src/main/kotlin/com/github/mihanizzm/ultistats/obs/ObsScoreboard.kt, ObsScoreboardService.kt, ObsScoreboardController.kt; src/main/resources/db/migration/V8__obs_scoreboard_profiles.sql. Modify obs/ObsNotificationStream.kt, realtime/MatchRealtimeSignal.kt, realtime/MatchRealtimePublisher.kt, service/MatchServiceImpl.kt, src/test/resources/schema.sql. Test src/test/kotlin/com/github/mihanizzm/ultistats/obs/ObsScoreboardIntegrationTest.kt.

**Interfaces:** Consumes persisted matches, match_teams(team_name,score,position), teams.photo_url. Produces GET /api/v1/obs/scoreboard/{matchId} -> {matchId,teams:[{teamId,teamName,photoUrl,score}],status,startedAt,endedAt,serverNow,profile}; PUT .../profile -> {shortNames,teamColors,swapped}; events match-changed and scoreboard-profile-changed carry matchId through /api/v1/obs/stream.

- [ ] Write API tests: default planned0:0/null clocks; persisted names/photos/scores/start/end; independent two-match profile; invalid UUID/unknown/deleted404; nonmember/malformed/null/blank/>80 name colors400; empty reset; no game/global mutation; save/start invalidations absent before commit and after rollback, delivered after commit. Verify DB payload independent read.
- [ ] Run focused tests RED: gradlew test --tests '*ObsScoreboardIntegrationTest' under required runtime/lock. Expected missing endpoint404 or missing new notification assertion.
- [ ] Implement immutable DTOs and snapshot REPEATABLE_READ JDBC; profile match lock+strict validation+transactional JSON upsert; V8 FK table and H2 test schema. Add OBS-only invalidations on accepted start/update, AFTER_COMMIT listener. No extra time columns.
- [ ] Run focused tests GREEN; existing ObsConfigIntegrationTest and MatchControllerTest PASS. Commit feat: add persistent per-match OBS scoreboard API.

### Task 2: Shared notification transport and scoreboard source

**Files:** Modify src/main/resources/static/obs/shared-worker.js; create obs-scoreboard.html, obs/scoreboard.css, obs/scoreboard-model.mjs, obs/scoreboard-renderer.mjs, obs/scoreboard.mjs, obs/scoreboard-client.mjs. Test src/test/js/obs-scoreboard.test.mjs and obs-shared-worker.test.mjs.

**Interfaces:** Consumes Task1 snapshot and event names. Produces elapsedLabel(snapshot, serverNowMs)->string, scoreboardTeams(snapshot,profile)->ordered teams; renderScoreboard(root,snapshot,profile,auto)->DOM; watchScoreboard(matchId,onSnapshot,onStatus)->dispose; preview messages {type:'scoreboard-draft',profile}, statuses {type:'scoreboard-status',message} with origin/source guards.

- [ ] Write tests with literal expectations: planned00:00, live63:12, future start clamp, finished63:12 remains after score correction; short name/manual color follows whole team when swapped, reset immutable; worker sends new events only matching matchId; terminal source remains alive, reconnect fetches canonical state, failed read preserves prior state, timeout releases retry, disposal cancels requests/timers.
- [ ] Run real node --test outside sandbox RED; expected missing modules/notification assertions.
- [ ] Implement model, single worker routing, Reconciler-backed watcher with bounded REST/1s safety reconciliation; monotonic anchored clock. Build safe textContent renderer, fixed1000x160 scale/contain/initials/contrast; initial empty source with no on-air errors; trusted draft messages only preview1.
- [ ] Run node --test src/test/js/*.test.mjs GREEN. Commit feat: render live OBS scoreboard with resilient clock.

### Task 3: Per-match draft editing in existing OBS admin

**Files:** Modify src/main/resources/static/obs-admin.html and obs/admin.mjs minimally; create obs/scoreboard-admin.mjs. Extend src/test/js/obs-scoreboard.test.mjs if draft helpers need extraction; browser proof Task4.

**Interfaces:** Consumes Task2 renderer/preview messages and watcher; mountScoreboardAdmin(matchId) binds selected match, snapshot, draft, iframe; source URL /obs-scoreboard.html?matchId=UUID. Existing selector calls it alongside EventLog openMatch. No global EventLog setting dependency.

- [ ] Add browser assertions before implementation to tools/obs135/check.mjs: selection creates scoreboard preview+stable URL; name/color/swap/reset changes preview immediately while two live sources unchanged; Save synchronizes same-match sources within2s, other match and game DB unchanged; switching match while delayed fetch resolves keeps newest selection/draft.
- [ ] Run bounded real browser RED against candidate API, expected missing scoreboard controls.
- [ ] Implement separate form names/UUID/color controls, draft reset/swap/save, postMessage on iframe load and each edit; watch committed snapshots without overwriting dirty draft; generation guards and disposal. Match input errors and fetch statuses appear only in admin.
- [ ] Run browser draft assertions GREEN plus node suite. Commit feat: edit scoreboard profiles in OBS admin.

### Task 4: Integration proof, documentation, CI and immutable QA handoff

**Files:** Create tools/obs135/check.mjs, tools/obs135/README.md, tools/obs135 fixtures/run scripts and docs/obs-scoreboard.md. No committed binaries/secrets/generated runtime data.

**Interfaces:** Consumes Tasks1–3 API/source/admin. Produces exactSHA PR, CI run URL and reproducible synthetic fixture/runtime manifest with jar SHA256/assets/commands/ports for permanent native OBS QA through lead.

- [ ] Extend browser/HTTP tests for two matches/six scoreboard sources+EventLog/admin on HTTP1: one SSE, REST usable, leases cleanup; server-accepted goal/undo/update/batch and late finished correction <=2s; planned source start; >60m including timeout/halftime; reload/clock skew; initial invalid/unavailable transparent; outage keep state+advance live clock then catch up endedAt; logos/long Cyrillic/initials/layout transparency screenshots.
- [ ] Run real PostgreSQL with Flyway full migration history, profile persistence after API restart, concurrent saves, transaction rollback/no notifications; isolate and preserve fixtures. Expected PASS and unchanged game/global rows after profile operations.
- [ ] Run full ./gradlew build, real node suite, deployment safety checks; expected PASS. Commit tests/docs then push/create one Refs135 PR; actual CI checks PASS on exact head.
- [ ] Give permanent reviewer exact head/spec/plan/evidence and fix meaningful findings RED->GREEN in same PR. Prepare immutable jar/hash/assets/fixtures and persistent separate runtime; verify endpoints from separate process. Callback lead for reviewer/nativeOBS QA and required Web/PO routing; do not claim nativeOBS PASS or close135.
