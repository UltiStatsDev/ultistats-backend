# Backend154 ingress and preserving upgrade evidence

Engineering slice only; Backend150/152/153/154 product acceptance remains OPEN until the atomic Backend/Web pair passes shared QA/PO. The independent QA24 matrix is not modified by these worker checks. The atomic152 integrator owns final full build, CI, review, available target and engineering callbacks;154 does not open a competing PR or self-merge.

## Version and limits

The final154 actual TLS/owner/DB checkpoint is branch head `c1fae2b`, immutable JAR SHA256 `7851135b6f44f9570bf2f390723e6ec7ba4a8956f0d32fe4dae28dc503463c14`. It exactly matched the integrator `e429dbf` production files:

- `src/main` tree `87ca213ddd712c2cb598a6cb23e771633724c8c5`.
- `build.gradle.kts` blob `afea8bdc37667b94b0df54b875a446b2084c48c9`.
- `deploy` tree `c12c7269b195e2d7f4d5e02f26deed3d9df0f20a`.

Later core nullable-JSON/OpenAPI corrections are a separate integrator delta. These scoped154 receipts do **not** certify all wire fields or schemas on the older checkpoint. Core retains its exact final JSON/schema checks. Ingress capability, configured origins and deployment configuration are unchanged by that model/schema delta; source equality must accompany any evidence reuse.

Original failing evidence is retained:062 actual nonweb CLI failed because a web controller required a servlet-only access bean; fixed d317 packaged CLI passed. Wrong-origin real HTTPS returned plain403 through default CORS before153's f6 fix; the original FAIL remains alongside the fixed exact-source PASS. An earlier full build executed680 tests with339 cascading legacy test-context failures from two catch-all chains; it is **not** called PASS. Core's fixture correction and final full-suite results belong to the atomic integrator. Focused ingress/CORS tests passed after their respective fixes; no unexecuted QA row is promoted by a unit count.

## Actual owned checks

- Real Tomcat anonymous/private sockets: Host, Cookie, Authorization, Origin, Forwarded and X-Forwarded-Proto cannot promote anonymous capability. Unknown socket is anonymous. Explicit loopback mode has no second private connector; forwarding strategies are rejected.
- Configured origin validation rejects userinfo/path/query/fragment/bad ports and LAN origins in loopback mode. Accessors serialize host case/default80/443 like browser origins, preserving one explicit secure-origin allowlist.
- Caddy2.11.7 binary SHA256 `7c446252fa0b1410187f5a22ff14c59269a95c78afc6a02ba64a4fb55b7ce462`: guest HTTP uses anonymous upstream without CA; per-request trusted CA succeeds; untrusted chain and unknown SNI reject; certificate state survives owned Caddy restart. Real source addresses127.0.0.2/.3 stay distinct despite forged client-IP/XFF. Native proxy harness uses probe upstreams and is not browser/native OBS/auth acceptance.
- Actual fixed Backend+Caddy HTTPS: host-only `__Host-` Secure/HttpOnly/SameSite=Lax/Path=/ cookies, masked JSON CSRF→unsafe request, wrong-origin RFC9457 `CSRF_INVALID`, invalid captured owner before CSRF materialization with zero Set-Cookie. A real source reaches20 generic login denials then429+Retry-After despite rotating forged headers; another real source has its own bucket.
- Actual owner bootstrap/recovery use packaged-JAR nonweb startup and private stdin. Temporary-password sports write denies before execution; own password change keeps logical owner/session. Session and activity survive API+DB restart. Logout, revoked-cookie bootstrap/relogin and owner recovery preserve account identity and revoke intended sessions. Raw credentials/cookies are absent from public evidence and source.
- Actual PostgreSQL loss returns only503 `{"status":"DOWN"}`; restart returns only200 `{"status":"UP"}`. The readiness oracle parses JSON, preserving exact keys/status while accepting whitespace. No readiness/installation read creates an administrator. Private plaintext connector rejects TCP through the host's non-loopback address.
- Deployment shell regression requires minimal UP readiness, stops failed candidate and records MAINTENANCE_REQUIRED instead of restarting old unauthenticated code; unresolved marker prevents further Docker changes. This shell suite is hermetic, distinct from actual runtime evidence.

## Actual247/V9 →71/V10 →auth/V11

Verified247 JAR `c6242f4455b55817e3c6af1513d3e418b546fc4e7104391cf8b9772b2d7d9edd`, source `24704e11143a0bf919559a41324603f936ae03cf`; its actual startup applied V1–V9 on a fresh owned DB. Seeded players/teams/memberships/live+finished matches/events/batch+undo receipts/OBS settings+profiles and two uploaded photos:11 tables,43 rows,2 file hashes. Quiet custom dump and uploads archive restored into separate DB/storage with all fingerprints matching.

Accepted71 JAR `d263e884aba8dbeaf2a65e571f8765ce9e3c23a8b945e87329f1b4c47785bbe1`, source tree equal to merge `5c9781f2895141ca7a430588873c764d8c1e2dd6`, migrated that same installation to V10. Original V9-column fingerprints and files matched. Added exact timestamp originals, a retained deleted event and a partial lifecycle pair; final V10 fixture was11 tables,72 rows,2 photos. Private V10 dump/uploads restore matched.

Actual auth startup applied V11 to the same installation and preserved every V10 sports/receipt/profile/original/file fingerprint. Before owner bootstrap, account/session counts were zero. A private V11 dump/restore preserved all16 tables and installation identity. After bootstrap/password/session/recovery tests, another quiet backup/restore preserved all16 populated tables:148 rows,2 photos, one account and retained session records. Legacy11-table/72-row/photo fingerprints still matched after all auth tests.

Maintenance rollback used the separate restored V10 copy, both ingress listeners stopped, old247 bound only loopback and a dedicated SELECT-only role without table mutations/schema CREATE. Old read200 worked; a deliberate old POST failed500 at database permissions, and all72 row/file fingerprints remained identical. This is no-write containment, not authentication in old code. ANY original metadata, including deleted events and partial pairs, keeps the71 hold. The old-finish→forward-export400 limit is retained, never repaired with inferred timestamps.

## Reproduction and private evidence

Source helpers:

- `auth154_ingress_proxy.py`: real isolated Caddy/TLS/routing/source/restart proof; explicit Caddy binary/config/output arguments; self-cleaned children.
- `auth154_http.py` and `auth154_client.py`: actual Caddy+Backend CSRF/origin/source-rate checks; original FAIL and final PASS outputs separate.
- `auth154_owner_http.py`: initial/resume/recovery phases; passwords and native Cookie jar only in private fixture files, never command arguments/JSON API/storage.
- `auth154_upgrade.py`: seed only the assigned247/V9 owned API.
- `auth154_snapshot.py`: read-only per-table stable-column fingerprints and file hashes, exact owner-label/path guards; compare migrated/restored DBs without printing rows.

Private artifact root `/tmp/ultistats-auth154` retains the immutable source/artifact manifests, per-phase fingerprints, backup checksums, CLI/HTTP receipts, guarded process inventory and cleanup receipt. Raw dumps, credentials, cookies, CA private state and uploaded data are not committed. The runbook is [auth upgrade](../../deployment/auth-upgrade.md); topology/trust instructions are [auth deployment](../../../deploy/auth/README.md).

## QA24 traceability

| QA row |154 evidence supplied | Remaining shared acceptance |
| --- | --- | --- |
| A03 | Actual listener/forwarded spoof, transport-first denial, no private plaintext exposure | Full endpoint matrix/Web paths owned153/Web/QA |
| A07 | Actual nonweb packaged CLI bootstrap/recovery and identity/session readback | QA's independent final-pair execution |
| A09/A10 | Owned HTTPS session/DB/API restart and captured-owner cookie rejection | Multi-device/browser delayed response matrix |
| A15 | Owner profile/device SYNC and pending/uncertain→DEFER runbook | Actual old-browser profile preflight owned Web103/QA; NOT_RUN here |
| A16 | Actual247/V9→71/V10→V11 DB/file/receipt/profile/original preservation, populated auth backup restore | Independent final-pair QA |
| A17 | Old binary SELECT-only closed-ingress recovery, ANY-original hold | Owner rollout remains separate |
| A18 | Native Caddy verified TLS/hostname/no-CA HTTP and explicit loopback connector tests | Real LAN browser/native OBS and device-specific staff trust NOT_RUN here |
| A19 | Configured origins, source trust, actual HTTPS cookies/CSRF/CORS | Real Internet DNS/ACME renewal and combined Web origin acceptance NOT_RUN here |
| A20 | Minimal DB-aware readiness and failure/maintenance delivery regressions | Web startup/printed URLs/foreign-port/clean-stop acceptance owned Web103 |
| A24 | Exact provenance and own resource cleanup receipt | Atomic source CI/review, final pair/general QA/PO and callbacks |

Self-consumer END followed all154 tests; no external154 runtime lease was offered or accepted. Exact guarded API/Caddy children stopped. Exact labelled temporary PostgreSQL container was stopped/removed under heavy→network EX locks; retained data volume, source/JAR/private backups/certificate state/evidence. Five owned ports closed; protected human/shared container IDs/images/running state unchanged, shared bridge retained. No prune, volume deletion or foreign process stop.
