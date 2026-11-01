# Event occurrence precision and PostgreSQL compatibility

Events keep canonical order by `sequenceNumber`. The ordering policy still rejects every genuinely earlier `Instant`; no timestamp tolerance or truncation is applied to validation.

V10 adds nullable `events.original_occurred_at` (ISO Instant text). New writes preserve the original nanoseconds there while `occurred_at` remains the existing PostgreSQL timestamp. Domain reads, lifecycle validation, responses, PATCH and portable transfer use the original only when it equals the physical value or rounds to that physical value at PostgreSQL's microsecond precision. The guard includes half-microsecond rounding and carry into the next second. Inconsistent or malformed metadata falls back to the known stored timestamp.

There is no backfill: V9 rows and writes made by an older binary have NULL metadata. Their lost nanoseconds cannot be recovered from a fingerprint. These rows keep their stored precision, including during later validation. PATCH still invalidates the immutable retry fingerprint and old batch receipts by revision; it preserves the event occurrence time, including on a no-op PATCH.

The migration is additive and does not rewrite events, identities, sequence numbers, revisions, fingerprints or receipts. A previous binary can map the original columns on V10 without understanding the new metadata. This proves schema/read compatibility, subject to the mutation boundary below. Its inserts leave metadata NULL. Bounded old PATCH checks keep occurrence time immutable and leave existing metadata intact; they are not a guarantee for arbitrary mixed-version writes. An out-of-band SQL change that produces a different physical timestamp is detected by the read guard. An attempted change entirely within the same rounded microsecond is unobservable in the legacy column; arbitrary direct SQL edits are outside the supported immutable-time command contract.

For a binary rollback, retain the V10 schema and metadata, run the read-only preflight below, and arrange maintenance/no-write access when retained metadata exists. The previous binary exposes its existing microsecond timestamps and existing validation behavior while it runs. Restarting the new binary recovers original precision for untouched/new-binary rows; old-binary inserts honestly retain stored precision. Do not drop the added column while a new binary is active. Public DTO shapes and the v1 support/deprecation status are unchanged.

## Verification

The normal `./gradlew build` includes H2 contract and precision tests. These do not prove PostgreSQL behavior. Against an **isolated disposable PostgreSQL 16 database**, set `TEST_POSTGRES_URL`, `TEST_POSTGRES_USERNAME`, and `TEST_POSTGRES_PASSWORD`, then run:

```sh
./gradlew test --tests '*EventBatchPostgresTest' --tests '*EventPrecisionPostgresMigrationTest'
```

`EventBatchPostgresTest` runs the common batch contract against real PostgreSQL/Flyway and adds a crossed-UUID concurrency test. Its test-only insert trigger widens the acquisition interval; the trigger is removed in `finally`. The migration test creates an owned random schema, migrates to V9, seeds events/revisions/fingerprints/batch and undo receipts, upgrades to V10, compares the data, exercises old-column writes, checks actual JDBC rounding, and removes that schema. Neither test is enabled without the explicit database URL.

For an actual HTTP/SSE check on an owned synthetic API, run `python3 src/test/python/batch71_http_race.py http://127.0.0.1:PORT /tmp/owned-race.json`. It checks six opposite-order UUID races, canonical order, loser reconciliation metadata and rollback, winner retry, score and actual SSE delivery once. Physical UUID acquisition uses one deterministic order across matches; assigned sequence numbers, responses and notification tails keep request order.

`src/test/python/batch71_runtime_compat.py` provides the three phases for a binary rollback/restart check. Run `seed` on the new binary, `old` on the previous binary against the same V10 database, then `new` after restarting the new binary, passing the same owned API URL and owned JSON proof path each time. This creates only synthetic fixtures. Retain the database/schema during the binary rollback.

The same V10 migration preserves `matches.original_started_at` and `matches.original_ended_at`. This keeps start/end constraints and portable match boundaries in the same precision as events: an event exactly at a persisted start is allowed, one nanosecond before is rejected, and finishing exactly at the last event remains exportable after reload. Raw timestamp fields remain JPA storage values; policies, DTO mappings and transfer use their guarded effective values. Start/end commands explicitly update the corresponding original value. These columns also stay NULL for legacy rows and old-binary lifecycle writes.

Rollback is a compatibility mode with the previous binary's precision semantics. It cannot record nanoseconds it does not persist. In particular, an old binary finishing a match with new-binary exact events may leave an end known only to microsecond precision; no historical end nanoseconds can be inferred from those events. Original metadata remains available when switching forward again, while NULL values retain their honest stored precision. Do not claim arbitrary mixed-writer history has exact original lifecycle boundaries.

## Downgrade boundary

Schema/read compatibility is distinct from safe mutation by a previous binary. The previous binary can read a precision-bearing match using its older microsecond representation. **Hold all old-binary mutations of that match during downgrade; resume mutations with the new binary.** This is an operator requirement, not an automatic guard implemented by this change. Do not describe old PATCH/insert/start/end checks as general mixed-version forward-consistency guarantees.

A conservative read-only preflight counts matches with **any** retained original metadata (including deleted events), even if the original happens to be aligned to microseconds:

```sql
SELECT count(*) AS matches_with_original_metadata
FROM matches m
WHERE m.original_started_at IS NOT NULL
   OR m.original_ended_at IS NOT NULL
   OR EXISTS (
       SELECT 1 FROM events e
       WHERE e.match_id = m.id AND e.original_occurred_at IS NOT NULL
   );
```

If this count is nonzero, require maintenance/no-write access for the previous binary and restore the new binary **before mutations resume**. The server does not automatically enforce this operational hold. A future deployment must plan this rollback constraint; this change performs no rollout.

Matches without retained submicrosecond originals continue to use the previous precision semantics. New-binary writes, validation, restart and new-to-new portable transfer preserve exact known values. Never drop metadata or infer a replacement end time to make a damaged mixed-writer history pass validation.

Observed limit: a new event at `.123456123Z` followed by an old-binary finish at either `.123456123Z` or `.123456122Z` produces the same physical end `.123456Z` with NULL original end. Forward reads preserve the known exact event and known stored end; export rejects the inconsistent boundary. The two requested ends cannot be distinguished from that stored state. Old export succeeds only because it also rounds the event. This is why unrestricted old-binary mutation is outside the supported downgrade boundary.
