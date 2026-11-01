# Backend161: stable helper implementation

Approved scope: Backend159/161; frozen Web spec/coordination commit a23e7144bac89731996f5eb1a2ae2309bb28beca. Base24704e11143a0bf919559a41324603f936ae03cf; no master/auth/V10 merge. Refs #161. Author does not merge or publish stable refs.

## Exact files and interfaces

- `deploy/stable/backend-release.mjs`: `main(argv)`, private JSON request/result CLI. Operations `inspect`, `fence-acquire`, `fence-recheck`, `fence-release`, `backup`, `postflight`, `rollback-plan`.
- `deploy/stable/helper-contract.mjs`: `validateRequest`, `validateManifest`, strict version/operation/transaction/path checks; sanitize typed result. Authoritative paired record consumed through W112 schema, never an invented descriptor.
- `deploy/stable/maintenance.mjs`: `observeInstallation`, `checkCompatibility`, `verifyQuietPoint`; Docker exact container/image/project/mount/network identity; SQL readiness, Flyway and live matches/activity queries; no guessed UUID/health endpoint.
- `deploy/stable/backup.mjs`: `createBackup`, private DB custom dump, uploads archive, private settings copies, SHA256 manifest; atomic completion only after recheck, failed output retained as incomplete. No restore/DB overwrite operation.
- `deploy/stable/tests/backend-release.test.mjs`, `maintenance.test.mjs`, `backup.test.mjs`: Node built-in test runner. Command runner injection only at OS boundary; test real guards/results/files.
- `docs/releases/backend-helper-v1.schema.json`: exact request/result/installation/fence/backup JSON contract; `docs/releases/backup-recovery.md`: lifecycle, failure recovery and limitations.

Proposed envelope: `{protocolVersion:1, operation, transactionId, installationManifestPath, targetRecordPath, targetRecordSha256, ...operationPaths}`. All paths absolute private files, JSON max bounded; secrets only inside private files, never stdout/argv. Common result `{protocolVersion:1, operation, transactionId, disposition, reason, evidence}`; dispositions `ok|blocked|maintenance_required`, exits0/2/3 respectively; malformed CLI exits64, unexpected failures70 with sanitized messages. Result atomic mode0600. Details of manifest and W112 adapter frozen with owners before implementation.

## Fence handshake to freeze

Web owns exclusive installation updater lock and stopping/restarting exact owned application writers (direct API included). Helper never stops processes. `inspect` is readonly and requires running managed API plus DB proof. `fence-acquire` after stop observes stopped exact managed writer IDs, fixed DB container+volume, private isolated Docker network with no published DB port and no unknown attached clients; checks actual IN_PROGRESS and pg_stat_activity again. It writes transaction-bound private receipt including manifest/record hashes and observed identities. `fence-recheck` repeats actual observations; receipt alone proves nothing. `backup` rechecks before and after dump/archive and only publishes complete manifest on success. `fence-release` verifies receipt and records release; Web decides restart. This is a stopped-managed-writer fence, NOT a persistent PostgreSQL lock; unknown/external writers/API block. Any networking layout requiring a weaker boundary must be explicitly redesigned with owners before code.

Initial compatibility: exact247 product source/migrations through V9 plus reviewed tooling; migration history must match actual bundled migration versions/checksums. Explicit candidate source/JAR/image identity supplied by162 and observed, not baseline aliases. Unknown current/target/schema/auth paths block or require maintenance for rollback. postflight checks intended image/source, preserved volume/origin, DB history/content and uploads/settings evidence plus bounded sporting HTTP GET. No Swagger-only readiness.

## RED / GREEN sequence

1. Commit this concrete plan, send owner handshake; inspect baseline SQL and Docker layout independently.
2. RED contract/guard tests: foreign container/project/image/volume; unknown external backend; active match; unknown migration; receipt replay/stale manifest; new writer before/after backup; incomplete/failed dump; secret stderr; unsafe rollback. Run `node --test deploy/stable/tests/*.test.mjs`, expected assertion failures before implementation.
3. Implement smallest agreed contract and guards; GREEN focused tests; record exact cases/results in local ledger.
4. RED private backup failure and postflight identity/data/HTTP tests; minimum implementation; GREEN all helper tests. Restore rehearsal belongs162, validate real PG and file hashes there; do not claim fakes prove runtime.
5. Run Node syntax/full helper suite, shell checks relevant to unchanged scripts, required baseline Gradle build under lock/JDK21 (reuse accepted sporting evidence only with product parity). Exact-head CI and independent permanent reviewer. Push own branch, PR with Refs only, technical callback exact hashes/contract/limitations. No human runtime/Docker until owned resource plan agreed.

## Review focus

Forged receipt must never bypass new observations; writer added after inspect must stop switch; archive/settings failure cannot claim complete backup; target/source image must be actual final SHA, never alias247. Backup failures never execute DB restore; unknown rollback leaves maintenance. Private data is never logged or committed. Stable publication and runtime QA remain separate gates.
