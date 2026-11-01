# Auth upgrade and maintenance recovery

Status: preparation for Backend154/150. Not a release instruction or executed upgrade receipt. Runtime execution awaits the frozen contract, core152/enforcement153 and an independently checked immutable artifact pair. Human installations, credentials, trust stores and DNS are outside worker scope.

## Before scheduling maintenance

Record exact old/new Backend and Web source SHAs, image/JAR hashes, schema history/checksums, configured public/secure origins and readiness URL. Source247 `24704e11143a0bf919559a41324603f936ae03cf` ends at V9; accepted source71 `5c9781f2895141ca7a430588873c764d8c1e2dd6` includes V10. Auth adds V11 through core152. Do not substitute a V10 fixture for a V9 source installation. Preserve V1–V10 migration bytes and installation UUID through backup/restore; sports JSON is not a full installation backup.

The owner inventories every browser, profile, device and old origin used for match input. Open each relevant old-origin profile using its original client and synchronize/reconcile pending and uncertain operations against canonical outcomes. Record owner SYNC acknowledgment per profile and the last canonical match revision. Known pending, uncertain operations, an unreachable profile with possible pending, or missing owner acknowledgment means **DEFER**. A blank store at a new HTTPS origin proves nothing about the old HTTP store. Backend cannot scan all browser IndexedDB. No first account adopts anonymous pending and no post-upgrade migration wizard is provided.

Verify the release-specific entrypoint map before flipping origins: LAN guest HTTP reads only; staff uses trusted HTTPS; Internet Web/API/uploads share one configured HTTPS origin. Explicit loopback HTTP is a separate development configuration with both backend and Web proxy bound to loopback. Guest must need no CA installation. Staff trust instructions must identify certificate chain, hostname and renewal procedure; never disable TLS verification. Caddy TLS state and configuration need persistent storage and private backups. No password or private key belongs in this runbook, a chat, CLI argument or source control.

## Private backup and restore rehearsal

Before migration stop input and all writers, fence old clients, and enter maintenance. Check the actual listener/container owner, process start identity, immutable artifact and database/storage identities; do not infer ownership from a port number alone. Close public mutation reachability before stopping the old service. Preserve current data until restoration is independently verified.

Create a private mode0700 backup directory and mode0600 custom-format PostgreSQL dump and uploads archive. Record tool/server versions, dump/archive hashes, source/schema identity and the same quiet boundary for DB/files. Preserve account/session installation data after auth, sports rows, deleted-event metadata, revisions, batch and undo receipts, OBS profiles/config, uploaded originals and photos. Do not publish raw dumps, environments, paths containing secrets or account/session credential rows in evidence.

Restore the backup into a separate owned database and separate storage directory first. Compare normalized DB fingerprints and every file hash, not just row counts or a successful pg_restore exit. Record table-specific stable-key fingerprints for players, teams, memberships, matches, events, batch receipts, undo receipts and OBS data; include original timestamp metadata, soft-deleted rows and partial lifecycle metadata. Snapshot core installation UUID when present. Preserve PostgreSQL/Flyway metadata for provenance separately from data fingerprints because migrations intentionally change schema history.

## Synthetic execution required before release

1. Start exact247 on a fresh owned PostgreSQL V9 database/storage; create sports/photo/receipt/OBS profile fixtures through actual supported interfaces. Save API/DB/file manifests.
2. Quiet writes, back up and prove private restore. Start accepted71 on that same owned installation; verify V10 and preservation of all V9 manifests. Add exact precision originals, including retained deleted events and partial lifecycle pairs, using the supporting version/test fixture seam. Save a second manifest.
3. Quiet writes again, private backup, start checked auth source on the same synthetic installation; verify V11 and unchanged sports/file/receipt/profile fingerprints. Check public readiness reports only UP/DOWN. No readiness/startup request creates an administrator.
4. Use the exact core152 owner CLI instructions with private stdin/hidden console on the synthetic installation, never password command arguments or public logs. Verify bootstrap once, password change, restart and stable installation identity. Core152 owns the final command syntax; this preparatory runbook does not invent it.
5. Verify public/private socket boundaries and direct-upstream isolation, real TLS trusted-chain/hostname acceptance and untrusted rejection, cookie Secure/HttpOnly/Path/SameSite and CSRF roundtrip. Browser/native OBS results are distinct from curl/JUnit evidence.
6. Execute the recovery path below against the synthetic copy and compare DB/file manifests. Attach exact hashes/commands and result paths to the final receipt. Prepared QA A03/A15–A20/A24 remain NOT_RUN until their actual scoped checks execute.

## Failure and bounded recovery

Do not use the generic old-image automatic rollback for the auth transition. Old binaries have no authentication enforcement; an additive readable schema does not make their network writes safe. Keep the failed installation behind maintenance and close every mutation entry, including direct upstreams, aliases, transfer and OBS admin. Preserve the failed state backup before any restore.

Preferred recovery is a verified compatible auth artifact against the retained schema. If unavailable, keep maintenance/no-write access while diagnosing or restore a selected private backup into a separate database/storage, validate manifests, then select a compatible binary explicitly. A backup restore can discard newer committed work; identify its quiet boundary and reconcile before promotion. Never restore onto an active writer or automatically drop/recreate a database. Do not drop V10/V11 columns/tables or erase auth identity to make an older binary start.

V10 additionally requires the conservative read-only query from [event precision](../events-v2-precision.md):

```sql
SELECT count(*) AS matches_with_original_metadata
FROM matches m
WHERE m.original_started_at_second IS NOT NULL
   OR m.original_started_at_nano IS NOT NULL
   OR m.original_ended_at_second IS NOT NULL
   OR m.original_ended_at_nano IS NOT NULL
   OR EXISTS (
       SELECT 1 FROM events e
       WHERE e.match_id = m.id AND e.original_occurred_at IS NOT NULL
   );
```

ANY retained original metadata counts, including deleted events, aligned values and partial pairs. Nonzero requires old-binary no-write hold until the precision-aware binary returns. Auth recovery maintains no-write containment for every old unauth binary even if this count is zero. Do not rely on proxy method filtering alone while a direct private port is exposed. Verify effective network isolation and denied writes rather than merely documenting intent.

The existing old-finish→forward-export400 limit remains: an old binary may persist a rounded finish before an exact event. Neither infer missing nanoseconds nor repair timestamps to make export pass. Preserve source metadata/evidence and return to the supported writer; arbitrary mixed-writer consistency is not promised.

## Promotion, postflight and cleanup receipt

Real promotion requires combined immutable Backend/Web QA and PO acceptance, then an explicit owner rollout scope. CD remains disabled until separately authorized; this document does not dispatch it. Before reopening input verify schema readiness, configured public/secure origins, correct protected login/docs/admin destinations, anonymous public viewing/uploads/OBS, protected writes and stable installation identity. Check known old client tabs remain fenced and owner preflight still applies. A failure keeps maintenance closed.

Each synthetic receipt records source/artifact/config hashes, schema versions/checksums, origins/ports/TLS trust scope, commands, fingerprint comparisons, restoration and denied-write outcomes, unexecuted checks, resource owner and consumer lease END. Consumer END precedes cleanup/archive. Stop/remove only exact owned unneeded processes/containers/networks under the agreed heavy/network locks; retain volumes, immutable JAR/source, private backups and evidence. No global prune, volume removal or foreign process cleanup. Product issues remain open until shared QA/PO acceptance.
