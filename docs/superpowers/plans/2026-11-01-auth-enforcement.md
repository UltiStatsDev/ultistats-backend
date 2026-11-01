# Auth enforcement implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Existing designated worker and independent reviewer apply; no duplicate implementation subagents. Autonomous execution is already approved.

**Goal:** enforce approved permissions and captured identity on every existing sports/OBS/docs method and transport while preserving public live reads and receipt semantics.

**Architecture:** one explicit surface policy consumed before controller/body handling by the core Spring Security chain, with entry classification before auth parsing. Statistics gating reads existing MatchService state; OBS and Swagger share an in-memory same-origin auth/CSRF adapter. No parallel auth types, migrations, session configuration or sports refactoring.

**Tech Stack:** Kotlin1.9.25, Java21, Spring Boot3.5.7/Spring MVC/Security, PostgreSQL, existing Node tests.

**Spec:** Web merge50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8, docs/superpowers/specs/2026-11-01-users-auth-design.md. Wire FROZEN c48eef30bff9e5e89e34da545b0aaad643e3c993 docs/api/auth-contract.md (auth-150-contract checkout), awaiting mutually frozen revision.

## Global constraints

- Inventory baseline5c9781f2895141ca7a430588873c764d8c1e2dd6; assigned branch agent/feature/153-auth-enforcement only.
- No product edits until lead publishes frozen wire and152 exact internal interfaces. Inventory/plan can commit now. Import approved prerequisite commits only when lead identifies them; no guessed wire.
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

- Created docs/api/auth-surfaces-v1.json and .md:57 application mappings,19 static files, generated/framework routes, method/transport/permission policy and verification matrix.
- Created tools/auth153/inventory.py: source snapshot/drift validation; runtime mapping assertions are still required.
- Planned create auth/security/AuthSurfacePolicy.kt: exact method/path rule classification, default deny, FINISHED statistics and preview classification. Class/package names are proposed153-owned names, not new core interfaces.
- Planned create auth/security/AuthSurfaceAuthorization.kt: adapter to152 permission/expected-identity and denial writer, invoked in the security chain before controller binding. Hook and ordering await152 publication; do not create a competing SecurityFilterChain or authentication filter.
- Planned modify config/WebConfig.kt only with152/154 coordination: remove wildcard credentialed risk in favor of published configured origin policy.
- Planned create static/auth/staff-session.mjs: in-memory context/CSRF, captured-owner checks and headers. Modify static/obs/admin.mjs, scoreboard-admin.mjs, obs-admin.html and generated Swagger initialization via a153-owned config resource/controller as agreed with152.
- Planned create AuthSurfaceMatrixTest.kt, AuthExpectedIdentityTest.kt and JS staff-session tests; add owned HTTP harness tools/auth153/check.mjs and sanitized receipts. Existing controller/service bodies remain unchanged unless a demonstrated enforcement integration requires a boundary edit.

## Core dependency gate and interface binding

The backend lead candidate proposes AuthEntryMode, AuthPermission, AuthPrincipal,
AuthRequestAccess.requirePermission(request, permission), validateExpectedIdentity(request, principal),
and AuthEntryClassifier.classify(request). These are **candidate references**, not a license
to define or assume them. Before product edits bind this plan to152's committed
exact packages, signatures, denial writer and chain hook. Required ordering:
connector transport policy → authentication resolution → CSRF/expected identity/
permission enforcement → MVC argument binding/domain. Anonymous transport must be
able to classify protected paths without resolving credentials. Statistics public
read may consult match status without granting private session access.152 owns all
auth/account routes;153 must not intercept them with competing sports defaults.

- [x] Verify clean assigned base and read actual controllers, static clients, SSE, ZIP and transfer bodies.
- [x] Write versioned inventory and plan; self-review against approved scope.
- [x] Commit inventory/plan and publish interface needs to lead (270e709, pushed assigned branch).
- [x] Receive frozen contract c48eef30 (SHA256 d9f83750f91758d95803ee8b06c85072c936eaa95bd4fe5867799e9ce873d5bf) and152 plan93d5fa9; shared SPI source commit pending.

### Task 1: complete policy and method coverage

**Files:** auth/security/AuthSurfacePolicy.kt; auth/security/AuthSurfaceAuthorization.kt; src/test/kotlin/com/github/mihanizzm/ultistats/auth/AuthSurfaceMatrixTest.kt; docs/api/auth-surfaces-v1.*.

**Consumes:** published152 AuthRequestAccess/AuthEntryClassifier/core denial writer and chain hook; existing MatchService.get(UUID):Match? and MatchStatus.FINISHED.
**Produces:** proposed `AuthSurfacePolicy.classify(method:String, path:String, previewValues:List<String>):SurfaceRule`, where SurfaceRule explicitly distinguishes PUBLIC, FINISHED_STATISTICS(matchId), permission-required and DENY. Final adapter signature bound only after152 publication.

- [ ] RED: parameterize every inventory row over guest/organizer/admin/temp and anonymous/private transport; runtime RequestMappingHandlerMapping set must equal reviewed application inventory plus explicitly core-owned mappings. HEAD inherits GET; wrong verbs/trailing slashes/encoded/semicolon/case/unknown paths never get implicit public grant. Verify generated docs endpoints against runtime, not source regex alone.
- [ ] Run targeted AuthSurfaceMatrixTest under shared JVM lock; preserve exact failure proving missing policy (not compilation/environment failure).
- [ ] GREEN: explicit allowlisted safe reads and permission rules, private receipt GET, POST import/export, photo aliases, lifecycle and all event commands. For statistics JSON/HEAD/ZIP read existing match state; missing/deleted never defaults FINISHED and retains404. Protect any preview=1 query value and OBS editor assets; public effective config/scoreboard and all three SSE routes stay public.
- [ ] Run matrix and existing StatisticsControllerTest/EventControllerTest/EventBatchControllerTest/ReplaySafeUndoControllerTest/MatchTransferControllerTest; update test authentication setup through152's published fixture only where needed, never disable security for auth assertions.
- [ ] Commit reviewed implementation with Refs153/150.

### Task 2: identity, transport and effect barriers

**Files:** AuthSurfaceAuthorization.kt and src/test/kotlin/com/github/mihanizzm/ultistats/auth/AuthExpectedIdentityTest.kt; shared chain integration only via152-owned hook.
**Consumes:**152 captured identity validation, current principal, auth denial/CSRF behavior;154 actual connector classifier. **Produces:** uniform raw ProblemDetail401/403 and no pre-controller domain effects.

- [ ] RED: each protected unsafe row with missing/partial/malformed/individually mismatched installation/account/session; forged valid other-session cookie; expired/revoked/blocked/temp sessions. Include malformed JSON and multipart so denials precede binding/files. No auth redirect or stale Set-Cookie clearing; ProblemDetail code/status/requestRejectedBeforeExecution:true exact.
- [ ] RED: anonymous connector with Cookie/Authorization/forwarded spoof, private GET/HEAD/CSRF/login/accounts/docs/preview and unsafe aliases returns403 PUBLIC_TRANSPORT_READ_ONLY before auth parsing; confirm session activity unchanged using152 synthetic persistence fixture.
- [ ] RED: commit original v2 batch and undo in own fixture, simulate response loss by ignoring successful response, expire/revoke session and retry frozen envelopes. Assert401/403 changes neither canonical revision/score/event positions nor batch/undo receipts/publications; known original receipt unchanged and unknown command not reserved. No inferred old-request rejection.
- [ ] Run named tests and preserve meaningful RED.
- [ ] GREEN bind core checks at published hook, preserve domain/event services unchanged, configured credentialed CORS with approved origins only. Resolve ordering findings directly with lead/152/154.
- [ ] Run focused identity + transport suites; commit.

### Task 3: secure OBS and Swagger adapters

**Files:** static/auth/staff-session.mjs; static/obs/admin.mjs, scoreboard-admin.mjs, obs-admin.html;153-owned Swagger initialization resource/config; src/test/js/auth-staff-session.test.mjs.
**Consumes:** frozen GET auth/session, GET auth/csrf JSON masked token and expected UUID headers; API_DOCS/OBS_ADMIN permissions. **Produces:** proposed `createStaffSession({fetchImpl})`, `bootstrap(permission)`, `protectedFetch(url, options, capturedContext)`; finalize exact exports in RED test.

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

## Current verification and self-review

Executed: inventory generation and --check (57 mappings,19 packaged files) at
baseline5c9781. No enforcement/product/security tests run yet. No runtime/fixtures
created. No auth capability claimed. Review focus mapped to Tasks1–3; real
connector coverage coordinated with154 rather than a MockMvc spoof substitute.
The only deliberate unbound implementation detail is152's not-yet-published
chain hook; it is a hard pre-edit dependency, surfaced to lead rather than guessed.

## Integration decision received after initial plan commit

Core152 plan93d5fa9 publishes packages/auth types. Worker coordination selected
the following **core-owned** extension;153 consumes it after frozen publication:

```kotlin
// com.github.mihanizzm.ultistats.auth.security
sealed interface AuthRequirement {
    data object PublicRead : AuthRequirement
    data object Core : AuthRequirement
    data object Deny : AuthRequirement
    data class Permission(val permission: AuthPermission) : AuthRequirement
}
interface AuthSurfacePolicy {
    fun requirement(request: HttpServletRequest): AuthRequirement
}
```

153 implementation `AuthSurfacePolicyImpl` replaces the proposed classify/adapter
split above: core owns chain/filter/transaction/denial rendering. Core handles its
auth/account/installation/health routes before extension dispatch; extension
unknown routes Deny. One requirement is saved under
`AuthRequirement::class.java.name` request attribute, avoiding duplicate match
status lookups. Transport requirement is evaluated before CSRF/cookie parsing;
public reads ignore cookie entirely. After CSRF, unsafe permission chain runs
inside core authority singleton lock and shared transaction manager, refreshing
DB authority and expected identity **after** lock acquisition, never trusting a
stale principal attribute. GET/SSE do not take mutation lock or renew activity.

Statistics UUID resolves via existing MatchService.get; only existing FINISHED
returns PublicRead. All other states/missing/deleted/malformed require
STATISTICS_READ_ALL; authorized requests retain controller404/validation400.
No missing-match public grant. Core default policy is Deny when153 absent.
No competing chain, AuthSurfaceAuthorization class or core configuration is
needed in153. Exact implemented type commit and wire freeze still pending.

Detailed expected test oracles are now versioned at
`docs/api/auth-enforcement-test-matrix-v1.md`:57 application cases,24resource
groups,25negative/effect cases. Every row is PREPARED/NOT_RUN. Independent source
annotation count and unique method/path pairs match57; multiline legacy team
uploadPhoto is explicitly checked.

## Reviewer authority-fence correction (before integrated chain code)

The earlier shared outer-transaction wording is superseded.152 owns an isolated,
bounded, unpooled PostgreSQL coordination connection with session advisory SHARED
fence for unsafe domain mutations, EXCLUSIVE for security management. Existing
sports service transactions remain untouched; **no outer Spring transaction spans
chain.doFilter**. This preserves appendBatch rollback-before-controller-catch,
commit-before-response and AFTER_COMMIT notifications. Domain permission/identity
is refreshed after fence acquisition; management takes its exclusive fence before
its own transaction/row guard. No upgrade, no GET/SSE fence, no password hashing
inside it.152 must publish exact budget/timeout/cleanup/lock-order implementation
and reviewer review before integration.153 consumes the boundary and verifies
real-PG cross-match UUID201/409, canonical position/SSEonce, commit-failure negative
control and revoke-before-blocked-write race through final chain; these are not
proved by current policy unit tests.

Source prerequisites imported:152 f8f548cd7e5017236ba25a1fe60b9598c1b5c091
(cherry-pickf09f8d3),154 origin SPI926b3eb (cherry-pick856cae7). Wire hash verified
by lead and mutualfreeze recorded; no further wire hold.

## Final authority design replacing the earlier correction

Independent reviewer rejected the separate coordination connection because its
death releases the fence while a different domain transaction could still commit.
152 now owns ordered advisors: actual transaction advice outerorder0, auth guard
innerorder1, matching both Spring/Jakarta via actual TransactionAttributeSource.
Inside the SAME existing domain transaction/thread-bound physical connection,
acquire PostgreSQL shared transaction advisory fence and revalidate current
authority/capture AFTER grant. Security management takes exclusive samekey first
in its own transaction before singleton/account/session rows. No outer HTTP
transaction, no independent fence connection, no upgrades, no GET/SSE locks.
152 owns narrow missing Team/Player CRUD, TeamPlayer.add/remove and Match.delete
transaction annotations;153 does not duplicate those edits.

Photo facade upload/delete methods already have Spring@Transactional, so advisor
must guard those before filesystem effects. An initial audit overlooked the
outer facade annotation; that incorrect callback-commit assumption was corrected
with152/reviewer immediately. File cleanup commit-failure behavior is separately
verified/dispositioned, never silently changed by a broad transaction wrapper.

Additional reviewer gates: supplied expected IDs reject before CSRF materialization
with zero Set-Cookie, including missing CSRF cookie. Bootstrap CSRF/login remain
usable with expired/revoked cookies per152 endpoint-specific policy.153 HTTP
harness checks each UUID mismatch on every protected unsafe inventory row with
malformed body/missingCSRFcookie, plus domain fingerprints and receipt invariance.
Actual run pending integrated chain/owned PG; syntax validation is not execution.
