# Issue71 batch boundary repairs

## Approved intent

Implement existing issue71 semantics, not a new API or field-test waiver. Current master24704e1 already implements the main pipeline. Independent actual PostgreSQL probes found two P2 gaps: equal9-digit client timestamps fail across batches after rounding-up; crossed shared UUID acquisition across matches can return500/40P01 instead of stable409. User delegated implementation decisions and explicitly requested autonomous approved work; no new product/design permission interview is needed.

## Engineering choices made by lead

Timestamp precision: retain the exact original event Instant in additive persistence metadata while retaining the existing occurred_at column and legacy fallback. Domain/API/policy reads must use trustworthy original precision when present. Legacy rows must retain their known stored precision without invented historical nanoseconds. The exact representation and trust/old-writer handling belong to the implementer and independent reviewer. Reserve additive migrationV10 if needed; never rewrite V1–V9.

Alternatives rejected: coarse rounding/tolerance in policy alone can accept genuinely backwards client time; retaining only a request fingerprint cannot recover arbitrary later submicrosecond order. Do not weaken timestamp ordering merely to make the example pass.

UUID concurrency: validate the request in its original order and assign canonical sequence numbers in that order, then acquire storage uniqueness in a deterministic UUID order. Response/log order stays canonical, never SQL insertion order. This avoids the proven crossed unique-key wait cycle while preserving atomic rollback and the existing typed UUID-conflict translation. Do not blanket-map arbitrary database faults to409 or add hidden retries/new identities.

## Constraints

- v1/v2 remain the same common pipeline/sequence policy; v1 stays supported/notdeprecated.
- Original UUID, payload, occurredAt and immutable retry binding survive commit/reload/restart; score/revision/AFTER_COMMIT notification once.
- Equal client timestamps across separate batches are valid, including rounding-up/down and a submicrosecond-later event; genuinely earlier timestamps remain rejected. Canonical sequence, not timestamps, defines order.
- New precision handling must cover append/update/read/retry without stale metadata, preserve lifecycle checks, document legacy-row precision and exercise upgrade/rollback compatibility. Do not claim lost legacy precision can be reconstructed.
- Winner in crossed UUID race applies all events once; loser returns documented409/code/current position/offending UUID where applicable, with no rows/receipt/revision/score/SSE partial effects.
- Public DTO shape and existing client migration status do not change. Field proof remains absent and required before future v1 deprecation.
- Only new owned synthetic resources; human18012/18081, all historical pins and reviewer19858/PG19636 untouched. Worker API19871/PG19471/proxy19872 reserved after free check.

## Evidence

Baseline actual HTTP probes: /tmp/ultistats-review-71/{timestamp-probe.py,timestamp-probe.json,cross-uuid-race.py,cross-uuid-race.json,api.log}. The first asserts RED; second records201/500 with40P01 and winner2/rev1, loser0/rev0. Preserve provenance; do not export raw archives automatically.
