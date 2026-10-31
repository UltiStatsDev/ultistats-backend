# Historical Backend Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for your assigned issue. Execution is already approved: separate issue worktrees and persistent independent review. Do not request another routine plan approval.

**Goal:** Deliver the fixed package #125 and #127–131 with verified integration callbacks.

**Architecture:** Keep Controller → Facade → Service → Spring Data and canonical event replay. Isolate contract corrections from runtime changes and statistics aggregates from unrelated metrics. Reuse OBS PR #126.

**Tech Stack:** Kotlin 1.9.25, Java 21, Spring Boot 3.5.7, springdoc 2.8.8, PostgreSQL/Flyway, JUnit/MockMvc; JavaScript tests for OBS.

**Spec:** `docs/superpowers/specs/2026-10-31-backend-cleanup-design.md`

## Global Constraints

- Scope: #125 and #127–131. #71 frontend/field work remains independent. #131 has first priority.
- No CD/deployment changes: CD is disabled and no remote server exists.
- Preserve v1/v2 clients, historical events, UNKNOWN snapshots and non-target statistics.
- Work only in your own worktree; no worker self-merge. Do not auto-close issues awaiting QA.
- Synthetic isolated environments only. Serialize JVM builds with `/tmp/ultistats-backend-gradle.lock` and coordinate browser/Docker use through `/tmp/ultistats-browser-network.lock`.
- Focused regressions, full backend build, CI and relevant real-PostgreSQL checks are required for code changes.

## Review Focus

- UNKNOWN scoring actors and point boundaries: #131 tests correct team attribution after halftime and undo.
- Zero versus unavailable average, timeout and an unfinished episode: #127 tests availability, denominator and completed-duration numerator together.
- Union/discriminator fixtures across v1 and v2 batches: #128 validates legal and illegal variants and compiles generated types.
- Omission versus explicit null with real serialization: #129 validates actual request/response fixtures against raw schemas.
- Relative instance and empty 404: #130 asserts HTTP body/content type and schema format validation together.

---

### Task 1: #131 — team break statistics

**Files:** New `service/statistics/BreakStatisticsAggregator.kt`; modify `model/statistics/TeamAttackStatistics.kt`, `dto/response/statistics/TeamAttackStatisticsResponse.kt`, the team mapper branch in `mapper/StatisticsResponseMapper.kt`, the statistics OpenAPI example and focused tests. Add `service/statistics/BreakStatisticsTest.kt`.

**Interfaces:** Consume `StatisticsAggregator.aggregate(MatchStatistics, List<Event>, Map<UUID,UUID>): MatchStatistics`. Produce integer `teams[].attack.breaks`, default 0, without modifying existing fields.

- [ ] RED table-driven assertions: pulling GOAL=1, receiving GOAL=0, pulling CALLAHAN=1 once, UNKNOWN scorer, successive opposite pulling teams, halftime reset, undo/deleted goal, empty log=0.
- [ ] Run focused tests and confirm the missing break computation fails.
- [ ] Implement canonical replay in a separate aggregate, add model/DTO field and mapping, update only the relevant statistics example/assertions.
- [ ] Run focused tests, full build and raw OpenAPI serialization checks. Verify existing totals remain unchanged.
- [ ] Commit, push and create a PR referencing #131 without auto-close; hand exact head and checks to reviewer. After merge the lead supplies Web #39 with contract and reproducible API instructions.

### Task 2: #128 — usable event unions

**Files:** `dto/request/CreateEventRequest.kt`, `dto/request/UpdateEventRequest.kt`, `dto/response/EventResponse.kt`; if necessary a dedicated `config/EventPolymorphismOpenApiConfig.kt`; new `controller/EventSchemaGenerationTest.kt` and focused generator/schema smoke tooling.

**Interfaces:** Existing JSON discriminator and variant fields remain unchanged. Produce raw schemas with no unproductive union/inheritance cycle; preserve `BatchEventRequest` and mandatory v2 eventId.

- [ ] RED raw-schema fixture validation for all variants and generator smoke: openapi-typescript 7.13.0 output must compile with TypeScript 6.0.3.
- [ ] Confirm baseline generation/validation failure, then correct schema modeling without broad runtime changes or disabling validators.
- [ ] Validate accepted and rejected v1/v2 create/read/patch fixtures, existing EventOpenApi tests, full build and generated TS.
- [ ] Document reproduction/check evidence in the PR, commit and hand exact head to reviewer. Do not edit #129/#130 config or genericize unrelated schemas.

### Task 3: #129 — nullability and PATCH omission

**Files:** `config/JsonNullableModelConverter.kt`, `dto/request/PatchRequests.kt`, nullable Create/Update/Upsert DTOs listed in #129, `dto/response/MatchParticipantResponse.kt`, `dto/response/statistics/ParticipantStatisticsResponse.kt`; dedicated nullability config only if required; new `controller/NullableOpenApiContractTest.kt`.

**Interfaces:** PATCH omission is unchanged; nullable city/plannedStartTimestamp/jersey number accept null, names/teamIds/playersPerTeam reject null. ALWAYS response properties are present and nullable. Avoid runtime serializer policy changes.

- [ ] RED schema/runtime pairs for PLAYER/UNKNOWN and statistics nulls; PATCH omission/null/value, explicit jersey clearing, nullable create and legacy update payloads.
- [ ] Fix only schema/converter metadata necessary to match these fixtures.
- [ ] Run the real runtime requests and raw-schema validation together; run focused/full tests.
- [ ] Commit and PR with exact scope/checks. Keep event-union and error customizers owned by #128/#130.

### Task 4: #130 — error schema accuracy

**Files:** New `config/ProblemDetailOpenApiConfig.kt` if needed; documentation annotations of v2 GET in `controller/EventBatchController.kt`; new `controller/ErrorOpenApiContractTest.kt`; targeted examples/docs.

**Interfaces:** ProblemDetail/EventBatchProblem.instance uses `uri-reference`. Preserve missing-match v2 GET empty 404 with its actual body/content type; document the real contract.

- [ ] RED invalid-page400, batch conflict, relative instance format validation and missing-match GET404 body/content type.
- [ ] Correct only schema/response documentation, not wire behavior or v2 batch/transaction semantics.
- [ ] Validate real error fixtures against raw schemas and run focused/full build.
- [ ] Commit and PR to reviewer; do not edit #128/#129 customizers.

### Task 5: #127 — completed holding averages

**Files:** `service/statistics/TotalTimeStatisticsAggregator.kt` or a separate dedicated aggregate; `model/statistics/PlayerTimeStatistics.kt`, `dto/response/statistics/PlayerTimeStatisticsResponse.kt`, the player-time mapper branch, relevant OpenAPI example and export handling; new `service/statistics/AveragePossessionTimeTest.kt`.

**Interfaces:** Existing numeric average remains compatible. Add `averagePossessionTimeAvailable: Boolean`; false distinguishes the legacy 0 placeholder from a measured zero. Average completed active episodes only; do not repurpose `discPossessions`.

- [ ] RED no completed episode => unavailable; 4/6/2 second episodes =>4000ms; completed zero episode =>available0; open suffix excluded; timeout excluded without splitting; interception starts ownership; scoring catches/CALLAHAN add no receiving episode.
- [ ] Add append/undo/rebuild and UNKNOWN/controller roundtrip coverage; preserve current total possession, pause and team time calculations.
- [ ] Implement completed duration/count tracking, availability mapping and documentation; preserve existing CSV columns and document availability compatibly.
- [ ] Run focused/full build, mapper/export/API regressions. Rebase after #131's mapper/example changes and rerun impacted checks.
- [ ] Commit, PR without auto-close, send exact head to reviewer. The lead supplies the Web follow-up callback after availability is verified.

### Task 6: #125 — adopt OBS PR #126

**Files:** Existing PR #126 implementation and tests only; any fixes stay on its existing branch/PR. Inspect its README/OBS instructions and V7 migration.

**Interfaces:** Same-origin overlay/admin, persisted shared config and committed SSE. Preserve existing v1/v2/diagnostic consumers.

- [ ] Adopt the exact existing PR branch, inspect diff/DoD and independently repeat full JVM, JS and relevant PostgreSQL migration checks.
- [ ] Fix concrete reviewer/QA defects in this PR with regression coverage; do not rebuild the feature or change agreed requirements.
- [ ] Remove obsolete human-only merge/auto-close metadata only through the approved review process; keep the issue open while actual OBS acceptance is pending.
- [ ] Provide immutable source, independent synthetic backend URL and precise test steps to QA; preserve user OBS settings.
- [ ] Reviewer handles checked-head merge; QA records actual OBS and independent-browser evidence separately. Record any exact environment blocker without claiming PASS.

## Coordination and completion

- [ ] Land this docs-only plan through independent review, validate links and `git diff --check`.
- [ ] Each issue retains its original acceptance criteria plus the approved amendments; cross-team bodies record mandatory callbacks and exact dependent issue links.
- [ ] Reviewer serializes merges, checks each current head and integration of preceding merges, and reports merge SHA/checks to the lead.
- [ ] Lead verifies integration availability and sends mandatory callbacks immediately; overall QA is not a callback prerequisite.
- [ ] Report technical package readiness to PO. Keep remaining QA issues open and do not auto-close product issues. Pause idle timers and retain the permanent reviewer.
