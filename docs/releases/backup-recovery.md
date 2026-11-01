# Stable backend backup and recovery

The helper is a local managed-installation protocol, not a deployment command. Public entrypoint remains Web `update.sh`. Read [the exact v1 contract](backend-helper-v1-contract.md) and [JSON schema](backend-helper-v1.schema.json) before writing a caller. The paired record schema is Web-owned; a helper success does not publish a release or replace shared QA/PO.

## Supported boundary

Initial application compatibility is proven product/migration parity with Backend247 through V9 plus reviewed release tooling. Exact source pairs in the finalized record are required; V9 alone, an image tag, checkout HEAD or an HTTP200 is insufficient. Inspect also accepts the already installed exact target pair for no-op. Unknown/new schema/auth/external Backend stops safely. No DB restore, migration, container stop/start, ingress reopening, queue deletion or operating-system installation command exists here.

The Web adapter owns its exclusive updater lock, browser pending/uncertain checklist, source verification/staging, ingress and all application writer lifecycle. The helper observes Docker/project/instance/image/mount/network identity and actual PostgreSQL state. Backend API and PostgreSQL have one isolated internal network and no published ports; only the exact owned nginx ingress connects the edge. There is no persistent PostgreSQL lock across helper calls. This is a stopped-managed-writer boundary for cooperating owners; Docker administrators and external/shared DB topologies are not claimed fenced. Unknown attached clients and live DB sessions fail closed.

## Update sequence

1. Inspect the original private installation manifest and exact finalized record/hash. Verify prerequisites, clean managed sources, actual source/product/JAR/image identity, matching migrations, zero IN_PROGRESS and sporting API response. Null migration/JAR expectations are allowed only for initial readonly identity observation, which returns actual verified values; persist them before maintenance.
2. Complete browser owner checklist and stage/build the exact pair before downtime. Close owned nginx ingress with transaction-bound503 config and actual challenge headers. Stop all exact owned API/app writers; **keep PostgreSQL and ingress running**.
3. Acquire a new private fence receipt. Recheck repeats actual stopped writer, isolated network, closed ingress, migration, active-match and DB-client observations. A prior success receipt cannot bypass a new writer or match.
4. Backup creates a NEW0700 directory,0600 DB custom dump, copied uploads tree, private settings copies and an `incomplete.json` marker. It verifies dump TOC, all public-table content including receipts/deleted rows, photo tree and settings hashes, repeats guards and content checks, then atomically publishes `manifest.json`. Existing paths are never overwritten. `incomplete.json` is retained even after completion as provenance; only a valid complete manifest and verified artifact hashes authorize consumption.
5. Start the exact target behind the still closed ingress. Describe a NEW target manifest with observed target writer IDs and exact image/JAR/source, retaining the original manifest. Postflight accepts changed writer IDs only with the same installation/DB container/volumes/origin and record-bound fence; compares actual DB content/uploads/settings against backup and probes the internal sporting API. The generated postflight receipt does not reopen ingress.
6. Keep the updater transaction pending/maintenance while committing installed state. Fence-release repeats target observation/data/internal HTTP checks and requires the matching postflight receipt. Only then may Web reopen ingress, verify external readback and journal complete success. Failed release/open/readback remains recovery/maintenance, never a completed successful update.

All request/result files are private regular owner files, no symlinks. Results/receipts are published atomically without overwrite. Consumers verify exit AND protocol/operation/transaction/disposition; absent or partial results mean failure. Raw command stderr, SQL rows, environment and credentials are not printed.

## Failures and rollback

Before a complete backup, retain incomplete artifacts, preserve original resources and use the Web owner recovery sequence. Never relabel a partial dump as valid. New writer/match/unknown state requires a new actual quiet-point check, not force. If the original process can be restarted safely, its owner does so under the existing maintenance transaction; helper failure itself never restarts anything.

After backup, `rollback-plan` is readonly and requires fencePath, backupPath and previousManifestPath. It proves the original manifest/pair/image/JAR was the fenced runtime, retains exact source/image evidence, rechecks stopped current writers and actual unchanged data/migrations/settings/photos, and requires the explicitly listed previous source tuple. Unknown or changed data returns maintenance_required; no automatic overwrite of the DB or files.

A proven compatible application rollback starts the previous binary behind the same gate. A new observed previous manifest has its new writer IDs. Postflight and release include the private `rollbackPlanPath`, recheck all data/runtime proofs, and use separate `.rollback-postflight.json` / `.rollback-released.json` receipts. Failure leaves ingress closed. The result reports the actual previous pair; it must not claim the target was installed.

Manual restore is an owner-controlled separate recovery action using preserved private artifacts, a separately provisioned empty synthetic/recovery DB and validated compatible application. Never restore onto the original installation merely because a target failed. Retain original volumes, private settings/photos, artifacts and rollback evidence until explicit retirement. A dump-list check is not restore proof: Backend162 performs an actual synthetic restore and compares DB/photo/settings/receipt evidence.

## Verification and limits

Node tests exercise guards, private files, transaction/receipt binding, failure stages and the full helper lifecycle with an OS-boundary simulator. They do not prove a Docker daemon, PostgreSQL utility availability, real restore or actual macOS execution. Actual Linux source build, owned PostgreSQL/restore/ingress rehearsal and independent review/CI are recorded separately for their exact source heads. Windows-native operation is outside this version.

The implementation bounds JSON input at4MiB, command output at16MiB (internal HTTP1MiB), individual DB dumps at256MiB and dump/copy commands at120seconds. Exceeding a bound fails rather than claiming success; larger installations need a reviewed streaming extension. The readonly runtime observer can inspect isolated dev identities for startup, but the updater CLI accepts stable ownership only. No host Python/jq/psql is required; the verified PostgreSQL image must supply sh/psql/pg_dump/pg_restore/wget and the managed ingress supplies cat/nginx. Docker/Node/Git must already be installed.
