# Batch71 boundary repairs implementation plan

> For agentic workers: use superpowers:executing-plans within this assigned worker. Independent permanent reviewer checks the final branch; do not spawn redundant implementers.

**Goal:** fulfill existing issue71 equal-time and stable UUID-conflict semantics on real PostgreSQL.

**Architecture:** preserve original event precision with additive persistence metadata and explicit legacy fallback; make physical uniqueness acquisition deterministic independently from canonical event order. Preserve the existing transactional common pipeline.

**Tech Stack:** Kotlin/Spring Boot/JPA/JDK21, Flyway, H2-focused tests, actual PostgreSQL16, existing HTTP/raw contract tooling.

**Spec:** docs/superpowers/specs/2026-11-01-batch71-boundaries-design.md and issue71; lead choices are implementation defaults, not claimed user-selected internals.

## Global constraints

- Scope only confirmed P2s. v1 supported/notdeprecated; no field-test claim or invented follow-up.
- No source/pin/human deployment. Only assigned own19871/19471/19872 resources.
- Gradle flock /tmp/ultistats-backend-gradle.lock, --no-daemon --max-workers=2; Docker/heavy/network locks as worker-template.
- ReserveV10 if precision metadata needs schema; additive old-row/old-binary compatibility, no edits V1–V9.
- Commit/Push approved task without another permission; Refs71 PR, no self-merge/auto-close.

## Review focus

1. Equal and increasing9-digit times across a persisted batch boundary versus a genuinely earlier time within the same microsecond: no coarse tolerance bypass.
2. Legacy rows and old-writer/rollback paths: absent/stale metadata must not fabricate a different historical timestamp.
3. PATCH/no-op PATCH/delete/undo/restart: immutable retry invalidation and current canonical precision remain consistent.
4. Crossed shared UUIDs with opposite request order and unrelated UUIDs: canonical order stays original and loser409 rolls back every side effect.
5. Start/finish precision boundaries and ordinary millisecond clients: no accidental lifecycle regression or v1 deprecation.

### Task1 — Persisted timestamp semantics

Files: model/EventEntity.kt; repository/jpa/EventInsertRepositoryImpl.kt; additive db/migration/V10__*.sql if needed; controller/EventBatchControllerTest.kt and a focused real-PG regression/migration test. Inspect exact existing patterns before naming new tests.

- [ ] Reproduce reviewer round-up RED on own fresh DB/current247; preserve round-down and same-batch controls.
- [ ] Add focused regressions: two batches same .123456789Z both201, exact canonical UUID/order/time; round-down control; later and truly earlier nanosecond values; v1/v2 common path; PATCH/retry/restart and lifecycle boundaries relevant to representation.
- [ ] Choose and document exact additive representation/trust rules; preserve original precision for new writes and honest stored-precision fallback for legacy rows. Implement smallest model/repository/mapping change; do not relax policy.
- [ ] Run focused GREEN on H2 and realPG; test V9-seed→V10 with legacy UUID/sequence/revision/fingerprint/receipts preserved and previous binary/schema compatibility. If deeper issue found, report lead before widening public contract.
- [ ] Commit focused precision repair with tests/docs.

### Task2 — Crossed UUID acquisition

Files: service/EventServiceImpl.kt, controller/EventBatchControllerTest.kt, focused real-PG concurrency regression. Controller changes only if deterministic acquisition alone leaves an identified typed-error gap.

- [ ] Reproduce opposite [x,y]/[y,x] valid timeout batches on separate own matches; verify baseline40P01/500 and loser rollback, not just status counts.
- [ ] Add reliable regression for concurrent crossed shared IDs; inspect any needed test-only coordination without changing production semantics.
- [ ] Keep validation/sequence assignment/response order unchanged; insert already-numbered entities in deterministic UUID order to prevent the known unique-key lock cycle.
- [ ] Require one201 and one stable409/code/position; winner exactly2/rev1, loser0/rev0/no receipt/score/SSE; also test same-match identical convergence, duplicate IDs, unrelated batches and request order controls.
- [ ] Commit deterministic acquisition repair.

### Task3 — Final integration and handoff

- [ ] Run complete ./gradlew build after final code; focused realPG/migration/contract checks and actual CI on exact final head. Do not repeat unrelated full UI/OBS suites without a new reason.
- [ ] Produce immutable own candidate JAR/source/hash/raw schema/actual HTTP proof; preserve baseline script provenance and own cleanup receipt. No raw archive export or human rollout.
- [ ] Open working PR Refs71, provide exacthead/checks/proofs to lead+permanent reviewer. Fix findings in same PR, no self-merge. Root is sole agreed checked merge actor.
- [ ] Lead dispatches targeted QA after actual checkedmerge/availability and handles PO acceptance; field limitation remains explicit.
