# Replay-safe undo engineering evidence

Backend issue https://github.com/UltiStatsDev/ultistats-backend/issues/144;
dependent Web https://github.com/UltiStatsDev/ultistats-web-ui/issues/78.
These are synthetic engineering controls, not shared product QA/PO acceptance.

## Native Chromium proof at c961181

Runtime source: `c9611818589e9f4bf0f4dc56eeb168c31023e973`.
Immutable JAR SHA256: `f867718b9d209744c7bdea73b8bdbdf26ec68b518f9bcc6bd3c8aa3090991744`.
Chromium156, one static HTML onclick/native fetch, no React/RTK/JS retries.
The proxy forwards each incoming request once and records a fully read real
upstream successful outcome before discarding/truncating the response.

- [Headerless control](c961181/headerless/conclusion.json): warmed GET and first
  POST share a client TCP port. After actual commit the proxy closes before headers.
  Chromium sends a second POST on another TCP port with the same envelope.
  Both upstream responses have the same SHA256. Canonical UUIDs go3→2, revision1→2,
  removed ID is exactly the observed PASS#3, and exactly one DELETED SSE is emitted.
- [Afterheaders control](c961181/partial/conclusion.json): real200 headers plus a
  truncated real response body produces a native TypeError and one POST. Canonical
  UUIDs go3→2, revision1→2, one DELETED SSE; GET recovers the recorded APPLIED outcome.
- [Restart proof](c961181/restart-after.json): after terminating and restarting the
  same JAR against unchanged PostgreSQL, both outcome lookups and exact POST retries
  match pre-restart receipts; canonical logs/revisions do not change.

Raw incoming JSONL, before/after canonical snapshots, real upstream outcomes,
receipts, SSE frames and screenshots are adjacent to each conclusion. The
[SHA256 manifest](c961181/sha256.json) covers all archived files. No real data or
user runtimes were used. An initial Playwright launch failed before any test due
to missing default downloaded binary; the actual batch explicitly used the
already installed `/usr/bin/chromium`. Neither control has a suite retry.

## Automated proof

At this stage full Gradle build:458 JVM tests PASS,26 existing JS tests PASS,
deployment safety checks PASS. New HTTP regression started with8 failing404 tests
before the endpoint existed. Nullable rejection raw-schema regression started RED
and passed after adding an explicit null union. Real PostgreSQL/Flyway focused
suite:43 tests PASS (16 new +19 batch +8 legacy). Raw OpenAPI AJV and generated
TypeScript strict compile validate11 actual HTTP fixtures, plus required/null
negative checks. AJV compiles before code generation; generator receives a clone.
This is scoped Undo DTO validation, not a claim about all existing Event DTOs.

The preliminary own DB history is V1,V1.1,V2..V7,V9, with independent V8 pending.
It is **not** final ordered upgrade proof. Before final integration, sync actual
master after independent scoreboardV8 and verify a fresh own DB through V9.
Later source/contract corrections and final verification are recorded below.

## Strict observed revision follow-up

Default Jackson accepted fractional1.5 as integer1. A focused HTTP regression
reproduced an unexpected200 mutation (RED); the new UndoEventRequest-only parser
now rejects fractions, strings and booleans as400 without reserving identity.
Frozen append parsing is unchanged. Full build459 JVM tests PASS; fresh separate
PostgreSQL focused suite45 tests PASS. Raw Undo DTO AJV/TS check still passes11
actual fixtures, with generator clone/raw immutability and negative request checks.
The c961181 native transport evidence predates this parser hardening; its envelopes
are valid JSON integers. Final exact-source runtime proof will be linked after
master/V8 ordering synchronization.
