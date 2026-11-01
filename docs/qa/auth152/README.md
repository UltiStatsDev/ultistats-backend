# Auth core engineering verification

This is an engineering record for the atomic Backend150/152/153/154 integration.
Product issues remain open until shared Backend/Web QA and PO acceptance. The
independent 24-row acceptance matrix is not marked PASS by these worker tests.

## Core checkpoint and reproducible checks

Code checkpoint `9cc801c` passed the complete PostgreSQL-enabled build: **719 JVM
tests, 200 in auth packages, zero failures and zero skips**. That includes both
new auth tests and older domain regressions. The latter use an explicit test-only
header-scoped chain in 25 legacy HTTP suites; they are domain coverage without
auth. New auth suites never import or inherit that fixture. In particular, the
older EventBatchPostgresTest inherits legacy domain isolation; the separate
AuthConcurrencyPostgresTest uses actual HTTP, the production chain and PostgreSQL.

Run with JDK21 and an isolated PostgreSQL URL/user/password supplied through private
environment configuration:

```sh
flock /tmp/ultistats-backend-gradle.lock ./gradlew build --no-daemon --max-workers=2
node --test src/test/js/*.test.mjs
bash deploy/tests/validate-workflow.sh
bash deploy/tests/resolve-release_test.sh
bash deploy/tests/deploy_test.sh
bash deploy/tests/auth-ingress_test.sh
bash deploy/tests/validate-compose.sh
```

The nine JavaScript test files and all listed shell checks passed. Deployment tests
are hermetic; no workflow was enabled/dispatched and no human runtime was changed.

Resolved packaged dependencies were inspected, including the actual JAR library
entries: Boot3.5.7, all Spring Framework modules6.2.19, Security6.5.11 and
bcprov-jdk18on1.86. Argon2id uses memory19456KiB, iterations2, parallelism1,
salt16bytes/hash32bytes. A production-encoder test verifies salted output and the
entire1024-code-point supplementary-Unicode password without truncation.

## Security and persistence cases actually exercised

- Fourteen-day true idle boundary; passive reads/reauth/password writes do not
  advance activity; explicit renewal retains logical identity and is monotonic.
- Temporary-account restrictions,300-second step-up, normalized unique login,
  literal search/pagination, role/block/reset revocation and concurrent last-admin
  protection on real PostgreSQL.
- Masked JSON CSRF roundtrip and credential-change refresh; host-only cookie
  attributes; transport-first denial, configured-origin CORS, complete captured
  identity before CSRF materialization and zero cookie refresh on mismatch.
- Current password change rotates the raw bearer but retains logical session,
  account identity and activity. Retired generation cannot authorize or revoke the
  replacement. Logout tombstones prove only the original credential/session.
- Real PostgreSQL queued revocation wins before a blocked protected writer. The
  writer rechecks fresh authority after its shared fence and leaves no effects.
- Killing only the owned writer's PostgreSQL backend releases its authority lock
  and rolls back that same mutation transaction. No separate lock connection can
  disappear while effects continue on another connection.
- Forced simultaneous cross-match event-UUID collision returns201/409; losing
  position/receipt state stays unchanged and only the committed event emits once.
  Deferred commit failure returns5xx and emits no AFTER_COMMIT signal.
- Authority acquisition preserves the original transaction's lock timeout, honors
  a stricter50ms caller bound and rejects shared-to-exclusive upgrades. Domain
  transaction boundaries and controller catch-after-rollback behavior are retained.
- Actual V9 baseline migrations pass through V10 to additive V11 without rewriting
  sports/receipts/profiles. V1–V10 remain unchanged. Installation survives reconnect;
  full upgrade/backup/restore and photo evidence belongs to the separately scoped
  [ingress record](../auth154/README.md).
- Full nonweb application bootstrap/restart and actual packaged-JAR owner CLI use
  explicit private stdin, create no default account/secret and open no HTTP server.

## Actual running artifact and schema

The owned loopback review artifact for `9cc801c` has JAR SHA256
`a7a1a5fa6cc3fdcdbba7e15d89525964b259ee3c57016e3557f7bbd84b722111`.
Its source manifests are:

- `src/main`: `0a6365f47f4ee969976e2321ae26171e53cc3b00`.
- build script: `afea8bdc37667b94b0df54b875a446b2084c48c9`.
- deploy tree: `c12c7269b195e2d7f4d5e02f26deed3d9df0f20a`.

Actual readiness and installation reads succeeded. Login and temporary password
change were exercised over HTTP; the current opaque cookie, logical session,
installation/account identity and last activity survived actual API restarts.
Public evidence contains no password/cookie/token values.

The application ObjectMapper's global NON_NULL setting initially omitted nullable
auth fields. A real-application regression reproduced it before the narrow
AuthContext ALWAYS fix. The untouched protected OpenAPI initially lacked matching
required/nullable metadata; another RED test preceded its correction. Actual
anonymous and authenticated bodies now validate directly against the untouched
schema; removing a required field is an explicit negative control. All11 context
fields are required, including nullable account/session/activity/reauth/replacement
fields. The frozen contract bytes remain SHA256
`d9f83750f91758d95803ee8b06c85072c936eaa95bd4fe5867799e9ce873d5bf`.

## Remaining integration gates and resources

The subsequent optional AuthHtmlEntryNavigation core hook passed its RED→GREEN
boundary tests plus HTTP/CSRF/CORS regressions. It allows only eligible HTML
document authentication navigation; API/assets/captured intents/mismatches keep
raw ProblemDetail. The concrete staff-entry allowlist/login return route and
genuine-activity cross-tab coordinator integration are owned by153/Web and remain
an explicit atomic-PR gate until their exact inputs and tests are supplied.

The final atomic head still requires its own CI, independent review, immutable
availability and checked merge. Engineering callbacks go immediately after actual
merge/checks/availability; product closure waits for shared QA/PO. Native OBS,
device trust, real LAN/browser and old-origin queue acceptance are not inferred
from these core tests.

The152 author owns the temporary PostgreSQL container/data volume and loopback API.
The private resource inventory records exact PID/start ticks/JAR/socket and consumer
leases. Superseded unleased APIs were stopped with exact ownership guards; their
source/JAR/evidence and all data remain. The current target must stay until consumer
END or a verified lifecycle handoff. Remove only owned unused containers/networks
after END; retain volumes, credentials/backups privately, source and evidence.
