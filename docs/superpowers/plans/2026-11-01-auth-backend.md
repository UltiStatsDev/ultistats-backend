# Auth Backend implementation plan

> Execution: user-approved persistent leads, real Backend worker chats, isolated
> worktrees and the existing independent reviewer. No duplicate implementation
> subagents. Code workers first commit their exact issue code plan, self-review
> its coverage and publish interfaces; dependent product edits await the mutually
> frozen wire contract. Ordinary planning/start is already approved.

**Goal:** implement Backend#150 and supply Web#96 and future Flutter#4/#5 with
the same secure, persistent auth contract while preserving sports data/queues.
**Architecture:** existing Spring Boot3.5/Kotlin/JPA/PostgreSQL; Spring Security,
opaque persistent server sessions, explicit activity, immutable expected identity;
separate anonymous/private entry capabilities and explicit release promotion.
**Spec:** published Web docs merge50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8,
`docs/superpowers/specs/2026-11-01-users-auth-design.md`.
**Wire:** `docs/api/auth-contract.md`, candidate1.0.0, Backend owner/Web mutual freeze.

## Global constraints and review focus

- Password8–1024 Unicode code points; ORGANIZER/ADMIN; one installation workspace;
  temporary password, local-only owner bootstrap/recovery, last-active-admin race.
- 14 days inactivity, not absolute lifetime; GET/poll/SSE are not user activity;
  persistence/revocation/multi-device and stale callback data safety are mandatory.
- Public live/reference/OBS; guest stats+CSV/ZIP only FINISHED; all protected
  legacy/v2/receipt/transfer/docs/admin surfaces and entry modes tested.
- Stable UUID installation/account/logicalsession; all protected mutations validate
  captured identity before effects, without rewriting frozen event payloads.
- No secrets in Web Storage/URL/Git/logs; no plaintext network staff auth; anonymous
  entry cannot upgrade privileges through credentials or forwarded headers.
- Synthetic247/V9→71V10→authV11, all existing data/photos/profiles/receipts retained;
  old-origin pending sync before enforcement/origin flip, otherwise defer.
- CD disabled_manually stays disabled; #151 lands before auth product merges.
  No human18012/18081/PG/shared19584/trust/DNS/credential rollout from this plan.
- Product heads/children OPEN until general QA+PO; PR Refs, no closing keywords;
  technical merge/checks/availability callbacks happen earlier to real consumers.

Review focus to pin in the owning worker tests:
1. Stale renew/login/logout JSON AND Set-Cookie after account switch: no identity
   resurrection, no newer-session revoke, no foreign frozen write (152/153 +Web99/100).
2. Auth denial after an earlier uncertain commit is not evidence that the earlier
   intent failed; preserve reconciliation, UUID/time/base and no unsafe replay (153).
3. Public listener with forged cookies/Origin/Host/forwarded headers and legacy
   aliases never reaches private login/context/mutation or passive activity (153/154).
4. HTTP old IndexedDB pending versus empty HTTPS storage never passes rollout
   preflight; no inferred browser-wide queue scan or first-account adoption (154/Web103).
5. Concurrent last-admin changes, recovery and revocation cannot remove management
   access, revive a revoked session or expose credentials (152).

## Task151 — explicit immutable promotion prerequisite

Files: `.github/workflows/cd.yml`, `deploy/tests/validate-workflow.sh`, relevant
deployment tests and release runbook. No application/JVM dependency change.
Consumes actual workflow355721783 disabled_manually plus unsafe source push master.
Produces manual immutable-SHA promotion and evidence that ordinary merge cannot deploy.

- [ ] Commit `docs/superpowers/plans/2026-11-01-auth-promotion.md` with actual file
  inventory, manual inputs/checkouts/image tags and exact test commands.
- [ ] RED: safety check rejects automatic master-push deployment and mutable image
  promotion on original source; do not actually dispatch/publish/deploy.
- [ ] GREEN: workflow_dispatch with explicit exact SHA, existing safety checks and
  production environment; no push deployment/latest promotion. Record actual
  GitHub disabled state without enabling it. Run existing shell safety checks.
- [ ] Commit, push, working Refs151/150 PR; independent review/checks; Root normal
  expected-head merge. Callback lead/Web immediately with source-gate proof.
  This internal task may close after engineering acceptance.

## Task152 — core identities, sessions, admin and owner CLI

Create the issue code plan `docs/superpowers/plans/2026-11-01-auth-core.md` before
product edits. New feature package `com.github.mihanizzm.ultistats.auth` contains
`model`, `repository`, `service`, `controller`, `security`, `config` boundaries;
avoid restructuring sports services. Modify `build.gradle.kts`/application config,
add ONLY `db/migration/V11__accounts_and_sessions.sql` (V1–V10 untouched).

Shared interfaces to publish in that code plan for153/154:

```kotlin
enum class AuthEntryMode { ANONYMOUS, SECURE, LOOPBACK }
enum class AuthPermission { SPORTS_WRITE, STATISTICS_READ_ALL, TRANSFER, OBS_ADMIN, API_DOCS, ACCOUNT_ADMIN }
data class AuthPrincipal(val installationId: UUID, val accountId: UUID, val sessionId: UUID,
  val permissions: Set<AuthPermission>, val passwordChangeRequired: Boolean)
interface AuthRequestAccess {
  fun requirePermission(request: HttpServletRequest, permission: AuthPermission): AuthPrincipal
  fun validateExpectedIdentity(request: HttpServletRequest, principal: AuthPrincipal)
}
interface AuthEntryClassifier { fun classify(request: HttpServletRequest): AuthEntryMode }
```

The code plan defines exact session/account/CLI classes, service signatures and
transactional persistence/crypto/cookie strategy; no consumer guesses these.

Published152/153 SPI: `auth.model` owns AuthEntryMode/AuthPermission/AuthPrincipal;
`auth.security` owns AuthEntryClassifier/AuthRequestAccess and
`AuthRequirement { PublicRead, Core, Deny, Permission(permission) }` plus
`AuthSurfacePolicy.requirement(HttpServletRequest): AuthRequirement`. Classify
once before cookie/CSRF handling; public-read skips credential loading, anonymous
private denial happens first, and protected mutation authority is rechecked under
the correctly ordered transaction fence. Principal request attribute is
`AuthPrincipal::class.java.name`. Core `AuthService.installationId(): UUID` is the
read-only154 accessor; `AuthProblem(status: Int, code: String)` supplies153 errors.
153 owns policy/WebConfig-CORS,154 owns connector/classifier implementation and
metadata/readiness HTTP,152 owns common types/security chain/CSRF/session store.
Read fences for domain mutation and exclusive security changes must select lock
mode before acquisition, installation first; no shared→exclusive upgrade or
long-running GET/SSE lock. Heavy password verification needs version recheck
without holding a global write fence for untrusted work.


- [ ] Freeze1.0.0 wire with Web first; independent security reviewer reads the
  contract and core plan. Resolve ordinary technical findings directly.
- [ ] RED tests: `AuthSessionLifecycleTest` idle boundary/poll/explicit activity/
  restart/revocation/multi-device; `AccountAdministrationTest` unique rename/temp/
  stepup/last-admin race; `OwnerAuthCliTest` noHTTP bootstrap/secretargs/logging.
  Pin exact cookie/header/CSRF JSON roundtrip and Unicode/no-truncation limits.
- [ ] GREEN entities/repositories/services/controllers/security adapter and CLI.
  Persistent authority is not a cached role or passive servlet last-access time.
  Canonical activity is monotonic; auth-version/revocation races fail closed.
- [ ] RealPG `AuthMigrationPostgresTest` and `AuthConcurrencyPostgresTest` exercise
  singleton installation/last-admin/revocation and247/V9→V10→V11 preservation.
  CLI uses synthetic hidden stdin only; no real owner credentials requested.
- [ ] Exact build/checks/independent review and immutable owned API. Merge only
  after151 source gate. Callback Web99/100/102 and Flutter-owner(PO), identifying
  precisely which slice works and which policy/ingress gates remain incomplete.

## Task153 — exhaustive transport/method/permission enforcement

Code plan `docs/superpowers/plans/2026-11-01-auth-enforcement.md` first. Modify
`config/WebConfig.kt`, existing controller/OBS/error boundaries where necessary;
create auth access-policy/request-matcher module and a versioned method/path
inventory. Keep authentication and sports sequence/receipt behavior separate.
Consumes152 `AuthRequestAccess`, `AuthEntryClassifier`, permission/principal types.

- [ ] Inventory every actual controller mapping/alias/static/SSE/receipt/transfer/
  statistics/export surface at checked base; classify entry+method+match-state.
- [ ] RED `AuthSurfaceMatrixTest`: guest/organizer/admin/temp/stepup/invalid owner,
  wrong method/alias/public transport credential spoof and FINISHED statistics.
  `AuthExpectedIdentityTest` asserts no sports/receipt/score mutation for all three
  mismatched headers, including legacy paths and uncertain prior commit.
- [ ] GREEN policy and raw ProblemDetail classification; no wildcard credentialed
  CORS, no HTML API redirects, no shared-cookie clearing from stale auth failure.
- [ ] Secure OBS admin and Swagger same-origin adapter fetches context/CSRF and
  injects expected headers; test no credentials in native public Browser Sources.
- [ ] Actual owned PostgreSQL/HTTP plus applicable existing event/undo/OBS regression,
  checks/review and immutable availability; callback Web99/100/101/102+Flutter(PO).

## Task154 — ingress, readiness and preserving upgrade

Code plan `docs/superpowers/plans/2026-11-01-auth-ingress.md` first. Own backend
transport connector configuration, readiness/installation metadata, opt-in auth
compose/Caddy/runbook and migration evidence. Coordinate exact file ownership with
152: common auth types in core; wiring/infrastructure in154. Web103 owns startup.

- [ ] RED `AuthEntrypointTest` actual public/private local ports and forged headers;
  readiness without protected docs; direct upstream/LAN proxy exposure; invalid
  certificate fails, guest sees no warning and staff trusted certificate succeeds.
- [ ] GREEN explicit LAN anonymous versus staff HTTPS and loopback-only topology,
  fixed configured origins, Caddy TLS state/renewal/trust forwarding and safe URLs.
  Do not install a CA in user/global stores or invent a production domain.
- [ ] Own fresh247/V9 synthetic dataset+photos+profiles+receipts →71/V10→auth;
  exact before/after fingerprints, private backup/restore and operational rollback
  that contains old unauth writes and preserves71 precision restriction.
- [ ] Document owner old-origin queue preflight: known pending/uncertain => defer,
  no claiming scan of all devices. Web103 verifies browser-side detection/sync.
- [ ] Checks/review/immutable origins/readiness/setup receipt; callback Web103 and
  Flutter(PO) before generalQA, then hand final pair to existing QA/PO. Human
  deployment remains a separate concrete post-QA/PO owner action.

## Shared final gate

Use QA `qa/auth-2026-11-01/acceptance-matrix.md` (24 PREPARED/NOT_RUN scenarios)
as an independent acceptance map, not as executed proof. Core152, policy153 and
delivery154 stay open until the exact Backend/Web pair passes full scoped QA,
native OBS/Event Log and PO acceptance. Preserve evidence and actual-version
provenance. Each completed author cleans only its own fixtures/resources after
consumer END, keeps volumes/artifacts, then archive/removes worker heartbeat.
