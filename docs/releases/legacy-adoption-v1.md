# Owned legacy adoption v1

This import-only extension belongs to Backend161 and Web114. It is a **separate durable adoption operation before the regular updater**, with its own journal and nonce. It preserves the actual unpublished Web39 / Backend247 application pair while changing only owned routing/container topology. The seven regular helper operations, immutable installation identity and normal `fence-release` are unchanged. A target-M record never authorizes reopening the original application.

Use only the reviewed new control module `deploy/stable/legacy-adoption.mjs`. Never execute a helper or batch file from the old checkout. Web verifies both complete raw control checkouts and their full SHAs before import, again before reopening ingress, and binds them through `installation.tooling:{webRoot,webSource,backendRoot,backendSource}`. The helper canonicalizes those roots, checks their actual HEAD/root identity and binds the metadata unchanged across adoption; this does not replace Web's raw-byte/source approval proof. Actual app roots and images remain separate from controller source identities.

## Imports and return values

```js
await inspectLegacyAdoption({ installation, claim, backupPath });
await backupLegacyAdoption({ installation, claim, backupPath, evidenceDirectory, transactionId });
await verifyLegacyTransition({ installation: canonicalOldPair, backupPath, evidenceDirectory, transactionId });
await completeLegacyAdoption({ installation: canonicalOldPair, backupPath, evidenceDirectory, transactionId });
```

Results have `protocolVersion:1`, `operation` (`legacy-inspect`, `legacy-backup`, `legacy-postflight`, `legacy-release`), `disposition`, `reason`, `evidence`. All except inspect also have the matching `transactionId`. Success is `ok/VERIFIED`; inspect refusal is `blocked`, later failure `maintenance_required`, with sanitized machine reason and empty evidence. No raw process stderr, SQL rows or secrets are returned. These imports have no CLI exit code and do not extend the regular CLI operation enum.

A successful backup additionally returns a **nonserializable same-process** `lease:{id,recheck(),release(),confirmDatabaseStopped()}`. Persist only evidence, not the handle. `JSON.stringify(lease)` fails intentionally. Recheck performs fresh process/session/locks/resource/source/data guards; a stored receipt or `maintenance:true` cannot replace it. Method failures expose sanitized machine reasons.

Successful inspect/backup evidence includes `ready`, `schema:9`, `activeMatches:0`, exact `current`, `runtime:{imageId,jarSha256,sourceSha}`, `sourceProof`, `catalogSha256`, `identity`, `compatibility`, `capacity`; inspect adds `dataFingerprint`. Backup adds `complete:true`, `backupPath`, `manifestPath`, `manifestSha256`. Inspect requires the original API running with a real sporting HTTP200; backup requires it stopped and reports retained artifact identity, not a running API.

`identity` records `instanceId`, actual project/API/DB IDs, `databaseImageId` and `originalDatabaseImageId`, original database/uploads volume names, unchanged API/Web origins, and sorted `storage:[{name,createdAt,driver}]`. PostgreSQL image comes from the actual original container, never a mutable tag or an assumed global fixture image. Postflight identity records the new canonical project/API/DB IDs while retaining original image/storage provenance.

## Recognized old installation

Claim fields: `schemaVersion:1`, `project`, `legacyRoot`, full `apiContainer` / `dbContainer`, `webSource` exactly39fc3f4ef736a21c5aa5d75e51b5b559ff4d33ba, `backendSource` exactly24704e11143a0bf919559a41324603f936ae03cf, unchanged `apiOrigin` / `webOrigin`, required `expectedJarSha256` from a verified old247 build, required `databaseName` / `databaseUser`, optional `settingsPaths` default[] (absolute private owner files). No passwords in JSON.

The installation must report actual old Web root, old Backend root `legacyRoot/dev/backend/.local/source`, `release:{version:"unpublished",webSource:39,backendSource:247}`, actual immutable old image, recognized project volume names and exact inspected IDs. Only loopback direct API binding is supported. Unknown/shared/remote layouts, public PostgreSQL binds, additional containers/network clients, mismatched credentials/JDBC target, JAR/source/migrations or storage identity stop before SQL locking or backup. Existing known loopback PostgreSQL publication is supported only with the live lease below; it never becomes an exception to the regular internal-only fence.

The owner registry is `actualOldWebGitDir/ultistats/stable-owner.json`, obtained from scoped readonly `git rev-parse --absolute-git-dir`, not an arbitrary claimed path. `installation.legacy.ownerPath` must match it. Private owner file binds instance, new installation root and exact old API/DB IDs. Web creates it exclusively and prevents duplicate adoption. Old tracked files/index are untouched; the exact247 gradlew.bat LF/CRLF exception is reported through sourceProof without normalization. No new application SHA inherits the exception.

## Live SQL and consistent backup

Web stages all needed source/images before downtime and owns its adoption journal/exclusive control. After inspecting the old pair and completing browser owner checks, it stops only the verified old API. The helper verifies actual stopped writer, original PG/volumes/project/source/JAR/network identities, PG16, full V1–V9/1.1 checksums, zero IN_PROGRESS and zero other clients.

Backup opens an owned bounded `docker exec -i ... psql` session. It acquires SHARE locks on **every actual public table including Flyway**, rechecks active matches after obtaining locks, and verifies the live backend PID/start time/application nonce and all granted table locks. This prevents table DML while the old direct API is stopped. A new match, unknown client, failed lock, dead/expired child or lost session refuses. No user query is cancelled and no foreign process/container is killed. The temporary lease is bounded at180seconds and never claims persistence after process death; expiry releases its own transaction and forces maintenance.

The helper uses the existing private custom-dump/uploads/settings backup primitive, capacity preflight and repeated content/quiet-point checks. It adds original image/storage/controller/catalog/source provenance to the private backup fence. Public-schema catalog evidence covers columns, constraints and indexes, separate from sporting-row fingerprints and full dump/restore evidence. Initial support is the explicitly verified247/V9 profile; no arbitrary custom/future schema compatibility is claimed.

Web holds the live handle and calls recheck immediately before stopping the exact old PG. Only after actual stopped-state confirmation may `lease.confirmDatabaseStopped()` write `backupPath/legacy-database-stopped.json`. Web always finally releases its owned handle. The old containers remain stopped and retained. Never run two PG instances against the same volume. A dead/released/expired holder cannot authorize another phase from its receipt.

## Canonical old pair and completion

Web creates a canonical owned internal-only PG/API/nginx topology, reusing the **same original volumes and original immutable PG/API images**, old app roots and origins. It does not create a replacement empty DB or change the app pair. API and PG have no published port; nginx alone holds the saved origin and transaction-bound503 gate. `adoptedStorage` provenance belongs Web; old volume names/CreatedAt/driver remain truthful rather than relabelled as newly created storage.

`verifyLegacyTransition` verifies old PG is stopped, actual new PG uses the original image and volumes, actual source/JAR/migrations/content/schema/photos/settings match the private backup, exact owned internal identities, zero active matches/unknown clients and the real closed ingress plus internal sporting GET. It writes `evidenceDirectory/transactionId.legacy-postflight.json`. No ingress is opened. `dataProof` contains database-content/catalog/uploads/settings hashes and migration fingerprint; no user rows/photos/settings values are included.

`completeLegacyAdoption` freshly repeats those observations and requires the matching existing postflight, original claim/backup and unchanged canonical installation/controller bindings. It writes `transactionId.legacy-released.json` with operation `legacy-release`; evidence adds `released:true`, `releasePath`, `postflightPath` / hash, `claimSha256`, `backupManifestSha256`, `installationSha256`, `controlSources:{web,backend}`, `controlSha256`. Installation/control hashes use canonical object serialization as defined by the producer, not arbitrary private JSON whitespace. This receipt proves completion eligibility; it does not claim the external API gate has opened.

Only Web's explicit legacy orchestrator rereads/validates and consumes that receipt once, revalidates actual raw controller roots, opens the **old** ingress, verifies external readback and finalizes its durable adoption journal. Normal `setIngressMaintenance(false)` still accepts only ordinary `fence-release`; no generic fallback is added. A failure remains maintenance/recovery with original containers/volumes/backup retained.

After completed adoption, the canonical old-pair manifest is the previous installation for a **fresh regular updater transaction**. Ordinary HTTP/source/queue/match/fence guards apply; a newly started match can block it. Adoption completion never means target M was installed or a stable version published.

Source tests simulate OS boundaries and exercise real private files/receipts/lease state. Actual owned PostgreSQL SHARE-lock contention, dump/restore, Docker topology, image utility availability and external readback require separately recorded engineering evidence. Actual macOS execution and shared QA/PO are not implied by these tests.
