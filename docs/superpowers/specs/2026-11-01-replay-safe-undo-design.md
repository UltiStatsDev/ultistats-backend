# Replay-safe undo design

Refs https://github.com/UltiStatsDev/ultistats-backend/issues/144; dependent consumer https://github.com/UltiStatsDev/ultistats-web-ui/issues/78.

## Root cause and baseline

At base 6ce9aad, v1 bodyless undo holds the match row lock but chooses the active tail on every invocation. Transport replay after commit can delete a second event. Baseline UndoLastEventControllerTest and EventBatchControllerTest pass; the legacy concurrent undo test explicitly expects two deletions. Independent native-fetch RED is archived in dependent Web evidence at bbbfed67b51271075c2ff55abb9254840f9ce0b1. A conditional-only command would prevent the second mutation but cannot identify a command-specific result after unrelated writes. Choose a durable receipt.

## Contract

POST `/api/v2/matches/{matchId}/events/undo` requires `{commandId: UUID, expectedLastEventId: UUID, expectedLogRevision: nonnegative int64}`. Identity scope is `(matchId,commandId)`; immutable payload binding covers both preconditions. Persist the complete envelope before dispatch and reuse it across transport failures/reload. Never substitute another ID for the same unresolved intent.

200 returns immutable `UndoCommandResponse`: matchId, commandId, expectedLastEventId, expectedLogRevision, outcome APPLIED or REJECTED, resultLogRevision, removedEventId (nullable UUID), removedSequenceNumber (nullable integer), rejection (nullable object with code and detail). All properties are required, nulls explicit. APPLIED has removed ID/sequence and null rejection. REJECTED has null removed fields and a rejection. The revision is historical, not current canonical state.

409 uses application/problem+json. Terminal guarded/policy rejection includes code and commandResult (the persisted UndoCommandResponse). Exact retry returns the same HTTP status/outcome, without writes or notifications. Same identity/different valid payload is UNDO_COMMAND_CONFLICT with no commandResult; GET retrieves the original. Empty log is UNDO_LOG_EMPTY; wrong revision or active tail is EVENT_LOG_STALE. Existing lifecycle/FINISHED sequence codes remain. Valid terminal rejections are durable, so a rejected intent cannot later execute. Invalid JSON, missing/null UUID/base, negative/noninteger/coerced string base return400 INVALID_REQUEST without reserving identity. Missing/deleted match returns404 RESOURCE_NOT_FOUND without reservation.

GET `/api/v2/matches/{matchId}/events/undo/{commandId}` returns200 recorded outcome, including REJECTED, or404 RESOURCE_NOT_FOUND for an unknown receipt or inaccessible match. A lookup404 is not evidence that an in-flight request cannot commit. Retain the same envelope and retry it to resolve. After known outcome, separately GET the v2 canonical log before accepting input. Client identity storage failures require keeping the unresolved barrier; legacy uncertain v1 operations cannot be assigned an identity retroactively.

Receipts have no TTL and remain for the lifetime of the match row. Soft deletion hides both mutation and lookup; it does not erase receipt storage. No authorization model is introduced. Structurally invalid requests and missing matches are not bound; clients must not repurpose their IDs anyway.

## Atomicity and compatibility

Under the existing pessimistic match lock: check receipt first; compare bound preconditions; otherwise check active tail and revision; apply existing removeLocked lifecycle/sequence policy; soft delete, recalculate score, increment eventLogRevision once, set lastEventLogInvalidationRevision, publish existing AFTER_COMMIT invalidation; insert receipt in the same transaction. Rejection also records under the lock. A unique database `(match_id,command_id)` constraint protects all instances. Rollback rolls back receipt and mutation together; restart loads persisted outcomes. GET takes the same lock, so waits for an already locked command rather than reporting a misleading absence.

V9 creates an independent undo_command_receipts table. V6 batch receipts and their invalidation semantics are unchanged. V8 is reserved for independent scoreboard work and must not be imported by this branch. Before integration, synchronize actual master and verify complete Flyway history. v1 undo remains non-idempotent and supported with its existing response/errors; Web must use v2 for replay safety. Frozen append, UNKNOWN attribution, score, FINISHED and legacy SSE payloads remain unchanged.

## Verification and delivery

Focused HTTP regression RED then GREEN; sequential/concurrent exact replay, payload conflict, stale revision/UUID, append/undo race, rollback, durable rejection, restart, exactly one revision/SSE, empty/missing/deleted/FINISHED, score/batch invalidation, legacy compatibility. Real PostgreSQL/Flyway is required in addition to H2. Check raw OpenAPI required/nullability/error schemas and strict generated TypeScript against actual response fixtures. Actual Chromium warmed keepalive: one native fetch, headerless close after real upstream commit, duplicated incoming POST must return same outcome with only one exact UUID deletion/revision/SSE. Afterheaders truncated-body control must not cause another mutation. Full build/CI and independent permanent reviewer. One PR Refs144; no self merge or automatic product issue closure. Immutable JAR SHA/runtime handoff on isolated19844/PG19444 only; Web callback after actual merge/checks/availability is owned by lead. Shared QA+PO acceptance remains outstanding until Web78 integration.
