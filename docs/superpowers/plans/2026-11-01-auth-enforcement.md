# Auth enforcement implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Existing designated worker and independent reviewer apply; no duplicate implementation subagents. Autonomous execution is already approved.

**Goal:** enforce approved permissions and captured identity on every existing sports/OBS/docs method and transport while preserving public live reads and receipt semantics.

**Architecture:** one explicit surface policy consumed before controller/body handling by the core Spring Security chain, with entry classification before auth parsing. Statistics gating reads existing MatchService state; OBS and Swagger share an in-memory same-origin auth/CSRF adapter. No parallel auth types, migrations, session configuration or sports refactoring.

**Tech Stack:** Kotlin1.9.25, Java21, Spring Boot3.5.7/Spring MVC/Security, PostgreSQL, existing Node tests.

**Spec:** Web merge50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8, docs/superpowers/specs/2026-11-01-users-auth-design.md. Wire FROZEN c48eef30bff9e5e89e34da545b0aaad643e3c993 docs/api/auth-contract.md (auth-150-contract checkout), SHA256 d9f83750f91758d95803ee8b06c85072c936eaa95bd4fe5867799e9ce873d5bf; mutual freeze confirmed.

## Global constraints

- Inventory baseline5c9781f2895141ca7a430588873c764d8c1e2dd6; assigned branch agent/feature/153-auth-enforcement only.
- Wire and shared SPI gate passed:152 f8f548c and154926b3eb imported. Import only published prerequisite commits; core transaction fence remains an integration proof gate.
- ORGANIZER has SPORTS_WRITE, STATISTICS_READ_ALL, TRANSFER, OBS_ADMIN, API_DOCS; ADMIN additionally ACCOUNT_ADMIN. Temporary password has no protected permissions.
- Guest statistics/CSV ZIP FINISHED only; sports/reference/photo/events/live/OBS sources all statuses public. Receipts private SPORTS_WRITE. Transfer POST export remains a guarded mutation.
- Every protected mutation validates three captured UUIDs and CSRF before execution. Auth401/403 never becomes domain409 or modifies receipts; unknown prior outcome stays unknown.
- Anonymous transport denies private auth/context/CSRF/accounts/docs/writes before credentials/body. Actual connector classification belongs154, no trusted arbitrary forwarding headers.
- 14-day idle: reads/SSE and ordinary writes do not renew. Core152 owns persistence/expiry/revocation and admin step-up300seconds.
- V1–V10 immutable; V11/core types/build dependencies/session configuration owned152. WebConfig CORS owner requires explicit coordination with152/154 via lead.
- CD remains disabled;151 promotion prerequisite before product merges. No human/shared fixtures, runtime updates, trust/DNS changes or deployments.
- JVM commands use flock /tmp/ultistats-backend-gradle.lock, JAVA_HOME=/tmp/ultistats-backend-jdk21, GRADLE_USER_HOME=/tmp/ultistats-backend-gradle-cache, --no-daemon --max-workers=2.
- Own target only: API21153 and PG22153 after free-port/ownership checks; all Docker heavyEX→networkEX, browser bounded networkSH. Retain volumes/JAR/evidence; cleanup exact owned resources after consumer END.
- Commit/push approved. Refs153/150 only; independent reviewer and Root merge; no selfmerge. Product issue OPEN until shared QA+PO.

## Review focus

1. Alternate verbs, legacy uploadPhoto/photoUrl, receipt GET, framework docs and preview query bypasses: explicit rows plus runtime mapping coverage fail closed.
2. Forged credentials on public connector: transport denial before parsing even malformed/multipart body, no private payload or activity update.
3. Missing/partial/malformed/mismatched captures for each UUID, including valid cookie of another account/session/installation:401/403 before domain effects, no cookie clearing.
4. Prior committed batch/undo with lost response then denial: unchanged receipt/canonical state and no false rejection/replay conclusion.
5. CSRF/identity capture stale between bootstrap and OBS/Swagger save: do not retry unsafe request under a new owner, preserve draft and surface actionable login/reload state.

## Published inventory and file ownership

- Created docs/api/auth-surfaces-v1.json and .md: original57/19, current71 application mappings/24static after core/ingress/staff-tool integration; every row labels core, ingress or enforcement ownership.
- Created tools/auth153/inventory.py: source snapshot/drift validation; runtime mapping assertions are still required.
- Planned create auth/security/AuthSurfacePolicy.kt: exact method/path rule classification, default deny, FINISHED statistics and preview classification. Class/package names are proposed153-owned names, not new core interfaces.
- Core152 owns auth/security/AuthSecurityConfig.kt, DefaultAuthRequestAccess.kt, denial writer and transaction advisors.153 supplies AuthSurfacePolicyImpl only; no competing security chain/filter or duplicated authority type.
- Planned modify config/WebConfig.kt only with152/154 coordination: remove wildcard credentialed risk in favor of published configured origin policy.
- Planned create static/auth/staff-session.mjs: in-memory context/CSRF, captured-owner checks and headers. Modify static/obs/admin.mjs, scoreboard-admin.mjs, obs-admin.html and generated Swagger initialization via a153-owned config resource/controller as agreed with152.
- Planned create AuthSurfaceMatrixTest.kt, AuthExpectedIdentityTest.kt and JS staff-session tests; add owned HTTP harness tools/auth153/check.mjs and sanitized receipts. Existing controller/service bodies remain unchanged unless a demonstrated enforcement integration requires a boundary edit.

## Published core binding

Core152 owns auth.model AuthEntryMode/AuthPermission/AuthPrincipal and auth.security
AuthRequestAccess, AuthEntryClassifier, AuthRequirement, AuthSurfacePolicy and
AuthProblem. Exact imported type sourcef8f548c; HTTP source062718e (domain advisor
checkpoint pending).154 owns AuthOriginConfiguration (926b3eb), classifier0d237a0,
AuthClientSource a36c2c3 and verified-source implementationb5902f7.153 consumes them.

```kotlin
// auth.security, core-owned
sealed interface AuthRequirement {
    data object PublicRead : AuthRequirement
    data object Core : AuthRequirement
    data object Deny : AuthRequirement
    data class Permission(val permission: AuthPermission) : AuthRequirement
}
interface AuthSurfacePolicy { fun requirement(request: HttpServletRequest): AuthRequirement }
```

AuthSurfacePolicyImpl.requirement(HttpServletRequest) is153's implementation.
Core handles auth/accounts/installation/health before dispatching to this SPI.
A single requirement is stored in request attribute AuthRequirement::class.java.name.
Actual connector transport and supplied expected IDs reject before CSRF/cookie
materialization/body; public reads skip cookie parsing. Core152 owns mutation
advisors at existing domain transaction boundaries, described below.

- [x] Verify clean assigned base and read controllers/static/SSE/ZIP/transfer.
- [x] Commit inventory/plan270e709 and matrixc6c3fe7; publish interface needs.
- [x] Receive mutual frozen wirec48eef30 and exact152/154 source interfaces.

### Task 1: complete policy and method coverage

**Files:** auth/security/AuthSurfacePolicy.kt; core152 chain integration (consumed); src/test/kotlin/com/github/mihanizzm/ultistats/auth/AuthSurfaceMatrixTest.kt; docs/api/auth-surfaces-v1.*.

**Consumes:** published152 AuthRequestAccess/AuthEntryClassifier/core denial writer and chain hook; existing MatchService.get(UUID):Match? and MatchStatus.FINISHED.
**Produces:** `AuthSurfacePolicyImpl.requirement(request:HttpServletRequest):AuthRequirement`; explicit PUBLIC/permission/DENY classification, with FINISHED-only statistics lookup and protected preview query.

- [ ] RED: parameterize every inventory row over guest/organizer/admin/temp and anonymous/private transport; runtime RequestMappingHandlerMapping set must equal reviewed application inventory plus explicitly core-owned mappings. HEAD inherits GET; wrong verbs/trailing slashes/encoded/semicolon/case/unknown paths never get implicit public grant. Verify generated docs endpoints against runtime, not source regex alone.
- [ ] Run targeted AuthSurfaceMatrixTest under shared JVM lock; preserve exact failure proving missing policy (not compilation/environment failure).
- [ ] GREEN: explicit allowlisted safe reads and permission rules, private receipt GET, POST import/export, photo aliases, lifecycle and all event commands. For statistics JSON/HEAD/ZIP read existing match state; missing/deleted never defaults FINISHED and retains404. Protect any preview=1 query value and OBS editor assets; public effective config/scoreboard and all three SSE routes stay public.
- [ ] Run matrix and existing StatisticsControllerTest/EventControllerTest/EventBatchControllerTest/ReplaySafeUndoControllerTest/MatchTransferControllerTest; update test authentication setup through152's published fixture only where needed, never disable security for auth assertions.
- [ ] Commit reviewed implementation with Refs153/150.

### Task 2: identity, transport and effect barriers

**Files:** src/test/kotlin/com/github/mihanizzm/ultistats/auth/AuthSurfaceMatrixTest.kt and tools/auth153/check.mjs; shared chain/transaction integration only via152-owned hooks.
**Consumes:**152 captured identity validation, current principal, auth denial/CSRF behavior;154 actual connector classifier. **Produces:** uniform raw ProblemDetail401/403 and no pre-controller domain effects.

- [ ] RED: each protected unsafe row with missing/partial/malformed/individually mismatched installation/account/session; forged valid other-session cookie; expired/revoked/blocked/temp sessions. Include malformed JSON and multipart so denials precede binding/files. No auth redirect or stale Set-Cookie clearing; ProblemDetail code/status/requestRejectedBeforeExecution:true exact.
- [ ] RED: anonymous connector with Cookie/Authorization/forwarded spoof, private GET/HEAD/CSRF/login/accounts/docs/preview and unsafe aliases returns403 PUBLIC_TRANSPORT_READ_ONLY before auth parsing; confirm session activity unchanged using152 synthetic persistence fixture.
- [ ] RED: commit original v2 batch and undo in own fixture, simulate response loss by ignoring successful response, expire/revoke session and retry frozen envelopes. Assert401/403 changes neither canonical revision/score/event positions nor batch/undo receipts/publications; known original receipt unchanged and unknown command not reserved. No inferred old-request rejection.
- [ ] Run named tests and preserve meaningful RED.
- [ ] GREEN bind core checks at published hook, preserve domain/event services unchanged, configured credentialed CORS with approved origins only. Resolve ordering findings directly with lead/152/154.
- [ ] Run focused identity + transport suites; commit.

### Task 3: secure OBS and Swagger adapters

**Files:** static/auth/staff-session.mjs; static/obs/admin.mjs, scoreboard-admin.mjs, obs-admin.html;153-owned Swagger initialization resource/config; src/test/js/auth-staff-session.test.mjs.
**Consumes:** frozen GET auth/session, GET auth/csrf JSON masked token and expected UUID headers; API_DOCS/OBS_ADMIN permissions. **Produces:** `createStaffSession({fetchImpl,origin})` with `bootstrap(permission)`, `prepareRequest(url,options)` and `protectedFetch(url,options)`; immutable internal owner capture, no new-owner retry.

- [ ] RED: adapter bootstraps permitted context then CSRF, sends same-origin credentials and captured three UUIDs plus X-CSRF-TOKEN on unsafe save. Missing/temp/denied/stale identity leaves draft intact and makes no unsafe retry. Context/CSRF in memory only, neither local/session storage nor URL. Delayed bootstrap/CSRF cannot adopt a new owner during save.
- [ ] RED: Swagger requestInterceptor guards unsafe try-it-out requests and private read capture; docs bootstrap does not recursively intercept auth bootstrap. Native public source import graph contains no staff-session import, token/URL credential or login call.
- [ ] GREEN shared adapter in OBS editors and Swagger; explicit login destination from configured installation secureOrigin if needed, no secrets in generated links, no unsafe automatic replay. Preserve existing preview behavior and messages except obsolete unauthenticated/trusted-network wording.
- [ ] Run node --test src/test/js/auth-staff-session.test.mjs and existing src/test/js/obs-*.test.mjs under appropriate bounded browser lease only for browser work; commit.

### Task 4: own HTTP/PostgreSQL, review and technical callback

**Files:** tools/auth153/check.mjs; docs/api/auth-surfaces-v1.md verification results; private sanitized /tmp/ultistats-auth153 evidence/runtime manifest.
**Consumes:** frozen152+154 integrated prerequisite SHAs, isolated fixture recipe, owned ports21153/22153. **Produces:** immutable checked artifact and consumer handoff; no product QA PASS claim.

- [ ] Check original-GID host ss/proc ownership and ports free; create named owned PG/API resources under bounded heavy→network EX. Record source SHA, JAR hash, PIDs/start ticks/ports/DB/storage and baseline fingerprint, secrets excluded.
- [ ] Actual HTTP/PG repeat critical inventory/FINISHED ZIP/alias/receipt/expected-owner/public spoof/CSRF/CORS/docs+OBS cases. Run full backend build and relevant Node regression with mandated JDK/cache/lock. Reuse earlier results only with explicit unchanged-source proof.
- [ ] Commit/push branch, open Refs153/150 PR via existing CLI, independently reviewed exact head, actual CI. Lead/Root merges after151 and dependency gates; worker never selfmerges.
- [ ] Verify exact merged artifact/availability, immediately callback lead and dependent Web99/100/101/102 plus Flutter-owner PO with checks/source/limitations; no secrets or internal coordination IDs in public PR.
- [ ] Deliver inventory + row-level evidence map to general QA; all unmatched24-matrix scenarios stay NOT_RUN/other-owner.153 remainsOPEN until shared QA+PO.
- [ ] After consumer END clean only owned processes/containers/networks under locks, retain volumes/JAR/source/evidence, receipt before archive.

## Reviewed transaction integration design

Core152 owns ordered advisors: actual transaction advice outerorder0, auth guard
innerorder1, matching Spring/Jakarta through actual TransactionAttributeSource.
On the SAME existing domain transaction/thread-bound physical connection, acquire
PostgreSQL shared transaction advisory fence, then refresh session/account/expiry/
permission/all3captured IDs AFTER grant. Require active nonautocommit transaction
and READ_COMMITTED so a blocked request does not retain a pre-wait stale snapshot.
Security management takes exclusive samekey first in its own transaction before
singleton/account/session rows. No outer HTTP transaction, independent fence
connection, upgrades, GET/SSE fences or hashing inside a held fence.

An outer HTTP transaction would break appendBatch's rollback-before-controller
catch and commit-before-response/AFTER_COMMIT behavior. An independent fence
connection can die while the domain connection commits after revocation. Both
approaches are rejected. Required actualPG proofs are cross-match UUID201/409
with loser position unchanged/SSEonce, real commit failure without success/event,
and kill-only-owned-fence-backend plus revoke-before-blocked-write race.

152 owns narrow missing Team/Player CRUD, TeamPlayer.add/remove and Match.delete
transaction annotations. Team/PlayerFacade photo upload/delete already have
Spring@Transactional: guard those before filesystem effects. Do not introduce a
new broad photo transaction wrapper; record commit-failure/file cleanup behavior
separately.153 consumes these152changes and does not duplicate shared service edits.

## Verification checkpoints (engineering scope only)

- Preparation270e709/c6c3fe7: independent source count/unique method-path57/19 PASS.
- dc14fe9 policy/UI/CORS: observed policy84RED→85GREEN, CORS2RED→2GREEN,
  staff adapter/interceptor missing-feature RED→15GREEN, plus40existingOBS NodeGREEN.
- 7b7d95c: actual Spring handler mapping equals reviewed58pairs, explicit ownership
  inventory prepares core/ingress integration; client HTTP harness syntaxPASS only.
- Imported152 c42ecc1/062718e and154 source/readiness/installation: inventory71/24.
 135policy+2CORS+1mapping+role matrix139JVM GREEN; later account HTTP matrix adds
 one test covering guest/organizer/temp denial, admin list, expired stepup no effects
 and fresh stepup create/PATCH/reset. Every protected unsafe enforcement route is
 also checked with7missing/partial/mismatched capture scenarios and missingCSRFcookie,
 asserting zero Set-Cookie and zero domain-entry-probe calls.
- Actual Chromium component PASS against synthetic HTTP: global/profile saves,
 captured headers, changed owner stops save/preserves draft, actual Swagger5.21
 try-it-out, fresh public source no auth module/request/cookie. It is not a real
 backend/PostgreSQL/native OBS proof. Temporary server/browser exited normally.
- Own PG22153 created under host originalGID/free-port guards and heavy→networkEX;
 exact resources recorded privately at /tmp/ultistats-auth153/resource-inventory.json,
 volume retained. No consumer leases yet. CLI actual full nonweb context exposed
 missing DefaultAuthRequestAccess injection from scanned AuthController;152 accepted
 the defect and owns conditional-web fix/full-context regression. No alternate
 bootstrap or synthetic owner creation was used. No API availability claim yet.

Remaining: fixed CLI+final domain-advisor integration, actual ownHTTP/PG matrix and
fingerprints, relevant full build, actual CI/independent review/checked merge,
consumer availability callback and cleanup after END. Shared24QA remains NOT_RUN.

### CORS rejection correction

Actual154 HTTPS repro showed Spring's default CorsFilter emits plaintext403.
153's AuthCorsProcessor retains DefaultCorsProcessor origin/preflight checks,
replacing only rejection with shared AuthProblemWriter. Actual anonymous/private
path denial keeps PUBLIC_TRANSPORT_READ_ONLY even with unapproved Origin;
otherwise CORS denial is403CSRF_INVALID, requestRejectedBeforeExecution:true,
no Set-Cookie or allow-origin grant. WebConfig provides the namedcorsFilter from
MVCHandlerMappingIntrospector; servlet auto-registration disabled so Security owns
one invocation. Web-only bean condition preserves the nonweb owner CLI.
Observed production-chain regression RED (missing problem MIME)→GREEN; valid
credentialed preflight still200 and bad-origin public/preflight/private-anonymous
responses have exactcode/body/no-cookie oracle. Final focused141JVM GREEN and
bootJar built. Actual154TLS affected-case rerun remains its owner gate.

### Native staff document login/return

Exact Web mapping received: returnTo with canonical /obs-admin.html and
/swagger-ui.html; /swagger-ui/index.html normalizes to /swagger-ui.html.153
StaffHtmlEntryNavigation consumes core0881aa0/6cfd5f5 guarded SPI. Only native
GET explicit HTML and approved staff document paths on SECURE/LOOPBACK navigate
302 to configured secureOrigin/login, or TEMP to /account/password; query/hash,
Host/forwarding and arbitrary return paths never carry. APIs/assets/XHR/POST,
expected-ID captures, unrelated403 and anonymous transport remain raw JSON.
HTTP only permitted for explicit loopback gateway. No cookie clearing.
Observed3RED of4 tests before implementation; final5navigation+core-hook/role/CORS
regressions GREEN /tmp/ultistats-auth153-html-final.log. Actual core-chain tests
exercise no-cookie/stale-cookie/temp documents, APIs/assets and public transport.
Full browser return after frontend login remains Web101/canonical-gateway gate.
