# Replay-safe undo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** One guarded undo intent deletes at most one observed event and has a recoverable durable outcome.

**Architecture:** Extend the existing transaction service with scoped immutable receipts under the same match lock. Add v2 command/recovery endpoints; preserve legacy and append behavior.

**Tech Stack:** Kotlin/JDK21, Spring Boot/JPA, Flyway/PostgreSQL, JUnit/MockMvc, Chromium, generated TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-01-replay-safe-undo-design.md`

## Global Constraints

- V9 independent undo receipts; no V6/V8 reuse or scoreboard implementation.
- No changes to human runtime or other fixtures. API19844/PG19444 only.
- JVM builds use shared flock and --no-daemon --max-workers=2.
- Browser bounded shared and Docker exclusive network lock.
- No self merge; PR Refs144; actual CI and permanent independent reviewer required.

## Review Focus

- Receipt lookup concurrent with first commit must not imply final absence.
- Rejected envelope must remain rejected after canonical state changes.
- Historical outcome must remain independent of subsequent edits/deletions.
- Transaction failure after score recalculation must not leave receipt/revision/SSE.
- Missing/deleted match and malformed envelope must not silently reserve usable identities.

### Task 1: Durable command and recovery

**Files:** create UndoEventRequest.kt, UndoCommandResponse.kt, UndoCommandReceipt.kt, SpringDataUndoCommandReceiptRepository.kt, UndoCommandController.kt, V9__undo_command_receipts.sql; modify EventService.kt, EventServiceImpl.kt, MatchProblem.kt, test schema.sql; test ReplaySafeUndoControllerTest.kt.

**Interfaces:** EventService.undo(matchId, UndoEventRequest): UndoCommandResult; undoOutcome(matchId, commandId): UndoCommandResponse?; result includes recorded outcome or validation/notfound/payload-conflict.

- [x] Write MockMvc tests expecting POST v2 replay to return identical removed UUID/revision and GET to recover outcome; no second deletion. Include terminal rejection binding, malformed and missing match.
- [x] Run focused tests and observe404 RED on absent route; preserve log.
- [x] Implement DTOs, SQL receipt uniqueness and receipt-first service transaction using removeLocked; controller returns typed result and ProblemDetail per spec.
- [x] Verify focused tests GREEN plus legacy/batch tests. Commit.

### Task 2: Transaction, race and schema proof

**Files:** tests ReplaySafeUndoControllerTest.kt / ReplaySafeUndoTransactionTest.kt; tools/undo144 contract/browser/PG verification scripts; README.md API section; OpenAPI customizer only if raw nullable schema needs correction.

**Interfaces:** Persisted outcome is immutable response; POST409 commandResult references it, GET200 returns it.

- [x] Add tests for concurrent exact replay, changed payload, append/undo race, stale tail/revision, empty/FINISHED, score, batch invalidation, single AFTER_COMMIT SSE and rollback (including receipt failure).
- [x] Run new tests RED before implementation corrections; then GREEN.
- [ ] Run same focused tests with isolated real PostgreSQL and Flyway, verify V1..V9 history after actual master sync and restart receipt persistence. Verify DB uniqueness.
- [x] Fetch raw OpenAPI and actual fixtures; generate TS and strict compile accepted/rejected/error fixtures, validate required and nullable schema. Commit.

### Task 3: Real transport evidence and review delivery

**Files:** tools/undo144 reproducible native fetch/fault proxy controls and synthetic evidence report.

- [x] Build exact JAR under JVM lock; start isolated persistent foreground API and verify readiness separately.
- [x] Run real Chromium warmed keepalive single-fetch headerless-close-aftercommit and afterheaders controls; assert incoming requests, exact canonical UUIDs, revision, outcome and SSE. Preserve raw evidence; no fabricated upstream success or suite retries.
- [x] Full gradle build, JS/deploy checks; push PR Refs144 and inspect actual CI.
- [ ] Request permanent independent reviewer with exact head, spec/plan, commands and immutable evidence. Fix actionable findings in same PR, verify changed paths and full checks.
- [ ] Supply immutable JAR SHA/runtime/fixtures handoff to lead; do not self merge. Lead owns technical callback to Web after merge/checks/availability and shared QA+PO.

## Execution ledger

- Baseline: clean branch agent/bugfix/144-replay-safe-undo at6ce9aad; legacy undo+batch focused suite PASS, /tmp/undo144-baseline.log.
- Ruling: use persisted terminal rejection as well as success — identity cannot change meaning after later state changes — extra receipt storage per rejected valid intent.
- Execution: native worker implementation, then existing permanent reviewer, as explicitly authorized; no new human design/plan gate.

- Task1/2 implementation: 8 route regression tests RED→GREEN; nullable rejection schema RED→GREEN; 43 focused tests passed real PostgreSQL/Flyway (V8 pending independent PR143). Full build PASS; raw schema AJV and generated TypeScript strict compile PASS on11 actual HTTP fixtures. Logs /tmp/undo144-{red,green2,schema-red,pg1,full1,contract1}.log.

- Ruling: reject fractional/string/boolean revision coercion only on UndoEventRequest — the observed integer position must match documented JSON types — clients sending a string revision must send an integer instead; frozen append is unchanged. Regression demonstrated fractional1.5 applied as1 (RED); scoped deserializer added.

- Task2 proof: strict revision regression RED→GREEN; fullbuild459 PASS, fresh own PostgreSQL focused45 PASS, rawAJV/TS11fixtures PASS before generator + deepclone/immutability. ActualChromium headerless1nativefetch/2POST→1deletion/revision/SSE and afterheaders1POST controls PASS atc961181; processrestart2receipt equality+exactretry canonicalunchanged PASS. Final orderedV8→V9 and permanentreview remain gated on actualmaster.

- Exacthead68b31d9: full459+CI3SUCCESS; immutablejar4c93bf3...; bothnativecontrolsPASS and2APPLIED/1REJECTED processrecoveryPASS, archive docs/qa/undo144/68b31d9. Permanent reviewer independently459JVM/45PG PASS; preliminarycode/runtime review ongoing. Portablehelperbase/proxyportfixed; compiled UpgradeProbe refuses missingV8. Finalmaster sync/orderedhistory/finalreview stilloutstanding.

- ReviewP2: checkedJsonProcessingException afterremoveLocked committedmutationwithoutreceipt underdefaulttransactionpolicy. OwnGOAL regressionRED expectedrev3actual4; fix scopedundo rollbackOnException; full460JVM GREEN verifiesprojection/event/revision/receipt/SSE rollback andexactenveloperetryonce. RealPGrecheck inprogress.

- Final: fixed reviewerP2 checkedreceiptexception atomicity — checked receipt encoding failure rolls back mutation projection revision and SSE RED→GREEN, suite460/460 and realPG46/46; noSQL/schema/wire/frozenappendchanges. PortablehelperbothcontrolsPASS onalternate19849; UpgradeProbe compiled/missingV8guardPASS (actualorderedupgradepending).
