# UltiStats auth-v1 contract

Revision: **1.0.0 candidate**. Owner: Backend lead. Product head Backend#150 / Web#96.
This committed candidate becomes frozen only after the Web lead acknowledges the
exact revision. Product code consumes the frozen revision; ordinary technical
freeze does not require another human or PO approval.

Approved requirements: Web docs merge `50bd486f3a2001b50b3e3f63d2ae4bbcf6f4c3a8`,
`docs/superpowers/specs/2026-11-01-users-auth-design.md` and its coordination plan.
Backend baseline: `5c9781f2895141ca7a430588873c764d8c1e2dd6`. Existing human/startup
backend `24704e11143a0bf919559a41324603f936ae03cf` is not changed by this contract.

## Identity and entrypoints

All installation, account and logical session IDs are canonical lower-case UUID
strings. A logical session ID is nonsecret; it is not the cookie credential.
Login rename, display-name changes and credential renewal do not change account
identity. Renewal does not create a new logical session. A new login creates a
new logical session. Queue owner is `{installationId, accountId}`; each intent,
late callback and logout intent additionally captures its match/session/generation
where relevant. A proxy URL is not installation identity.

An additive migration creates the installation UUID once. Database backup/restore
preserves it; sports JSON transfer never imports or overwrites it. A new database
gets another UUID. V1–V10 are immutable; auth-core reserves V11.

Three explicit deployment entry modes exist: `ANONYMOUS`, `SECURE`, `LOOPBACK`.
Anonymous LAN HTTP serves only public reads; it does not inspect credentials to
grant more access. Auth/session/CSRF/account/private APIs and all protected writes
return 403 `PUBLIC_TRANSPORT_READ_ONLY` before authentication/body processing,
even with forged Cookie/Authorization/forwarded headers. No private context leaks.

SECURE is HTTPS to the browser, with a private trusted ingress/upstream. Actual
connector/listener configuration determines entry capability; a client header,
Host, Origin or X-Forwarded-Proto cannot upgrade ANONYMOUS. Upstream exposure and
trusted forwarding are part of the ingress plan and tests. LOOPBACK permits an
explicit HTTP development mode bound only to loopback, with a loopback-only Web
proxy. It must not be exposed through a LAN/public proxy. No automatic TLS bypass.

Network origins are configured, not inferred from arbitrary request headers.
Internet uses one HTTPS origin for Web/API/uploads. LAN may have distinct public
HTTP and staff HTTPS origins. No production domain or CA is provisioned by tests.

### Public installation and readiness

`GET /api/v1/installation` returns `InstallationInfo` on every entry mode:

```ts
type UUID = string; // canonical lower-case UUID
type Instant = string; // ISO-8601 UTC, no client timestamp used for session expiry
type EntryMode = 'ANONYMOUS' | 'SECURE' | 'LOOPBACK';
interface InstallationInfo {
  contractVersion: '1.0.0';
  installationId: UUID;
  entryMode: EntryMode;
  publicOrigin: string; // configured origin, no path/query/credentials
  secureOrigin: string; // configured HTTPS origin, or explicit loopback HTTP
}
```

No account/session/csrf/credential/admin-count payload appears here. Guest login
navigation uses configured `secureOrigin + '/login'`; return paths are restricted
to the configured origin and permitted relative paths, never arbitrary redirects.
`GET /health/ready`: 200 `{"status":"UP"}` or 503 `{"status":"DOWN"}` only.
It verifies application/database/migration readiness without using protected docs;
it neither creates an admin nor publishes setup/account details.

## Secure context, credentials and CSRF

Use Spring Security and a PostgreSQL-persistent opaque server session. The final
core plan records the repository/filter implementation and compatible patched
dependency versions. No JWT/access token is put in Web Storage, URL or JSON.
Session credentials are cryptographically random; database storage must not make
the raw bearer cookie available in normal account/domain exports or logs.

HTTPS cookie: `__Host-ULTISTATS_SESSION`, HttpOnly, Secure, SameSite=Lax, Path=/,
no Domain. Explicit loopback HTTP uses `ULTISTATS_LOOPBACK_SESSION`, HttpOnly,
SameSite=Lax, Path=/, no Domain. Loopback credentials are not staff-LAN credentials.
The cookie is persistent across browser restart. Canonical idle expiry is server
controlled, 14*24h since genuine user activity; active renewal extends persistence.

`GET /api/v1/auth/csrf` is secure/loopback only and returns:
`{headerName:'X-CSRF-TOKEN', token:string}`. Token stays in memory, not storage/URL.
Use the framework CSRF protection/repository and its SPA login/logout refresh
semantics; unsafe methods including login/logout require the CSRF header and
matching cookie state. CSRF cookie is host-only, Path=/, Secure in HTTPS mode.
No wildcard credentialed CORS: allow configured secure origins only; reject
unapproved Origin/Referer when present. Native cookie-jar consumers still obtain
and send the CSRF token; they do not gain an HTTP/public transport exception.

CSRF repository: Spring CookieCsrfTokenRepository; HTTPS cookie
`__Host-ULTISTATS_CSRF`, loopback cookie `ULTISTATS_LOOPBACK_CSRF`, HttpOnly=true,
SameSite=Lax, Path=/, no Domain. Use the default XOR request attribute handler;
GET csrf returns its request-attribute token, and the client sends that exact
masked token in X-CSRF-TOKEN. Web never reads the cookie directly. Integration
tests must prove GET token → unsafe request works and token refresh after
login/password/logout. Do not mix a plain-header SPA handler with a masked JSON
token. A fresh CSRF fetch is part of the single-flight auth coordinator.

```ts
type Role = 'ORGANIZER' | 'ADMIN';
type Permission = 'SPORTS_WRITE' | 'STATISTICS_READ_ALL' | 'TRANSFER'
  | 'OBS_ADMIN' | 'API_DOCS' | 'ACCOUNT_ADMIN';
interface AccountView {
  id: UUID; login: string; displayName: string; role: Role;
  status: 'ACTIVE' | 'BLOCKED'; mustChangePassword: boolean;
  createdAt: Instant; updatedAt: Instant;
}
interface AuthContext {
  contractVersion: '1.0.0'; installationId: UUID; serverNow: Instant;
  state: 'ANONYMOUS' | 'AUTHENTICATED' | 'PASSWORD_CHANGE_REQUIRED';
  sessionId: UUID | null; account: AccountView | null;
  permissions: Permission[];
  lastActivityAt: Instant | null; idleExpiresAt: Instant | null;
  reauthenticatedUntil: Instant | null;
  replacedSessionId: UUID | null;
}
```

All context fields are present; nullable fields are JSON null. ANONYMOUS has no
account/session/permissions. A temporary-password session has account/session,
but empty protected permissions. ORGANIZER gets the first five permissions;
ADMIN additionally gets ACCOUNT_ADMIN. Server authorization is authoritative.

`GET /api/v1/auth/session`: no cookie => 200 ANONYMOUS. Valid cookie => 200 context.
A presented expired/revoked/blocked session yields the appropriate auth denial.
This private endpoint is never available on ANONYMOUS transport. It does not
extend inactivity, as do neither GET polling nor SSE connections/heartbeats.

## Frozen expected identity

Every protected mutation, including legacy aliases, lifecycle, batch, PATCH,
delete, undo/receipts, transfer, settings and admin mutation, supplies:

```
X-UltiStats-Installation-Id: <captured installation UUID>
X-UltiStats-Account-Id: <captured account UUID>
X-UltiStats-Session-Id: <captured logical session UUID>
X-CSRF-TOKEN: <current in-memory CSRF token>
```

Backend checks all three IDs against the authenticated current session BEFORE
any controller/domain mutation. Missing => 403 AUTH_CONTEXT_REQUIRED; any mismatch
=> 403 AUTH_IDENTITY_MISMATCH, never domain409 and no guessed identity adoption.
Private reads should carry the same capture; supplied mismatches fail closed.
Public reads remain public and must not reveal authenticated private additions.
Login requires the expected installation header, but no pre-existing account.

Web owns one session/auth coordinator, coalesces renewal and serializes credential
changing login/logout/renew/password flows, including same-profile tabs. Every
response is applied only to its captured generation/owner. Stale Set-Cookie and
late login/renew responses are a review focus, not just stale JSON callbacks:
reconcile an unexpected cookie context explicitly, never restore another identity
or send that identity's pending wire. Expected-header enforcement is the backend
data-safety barrier even when browser cookie ordering changes.

## Operations and exact request shapes

All auth APIs use JSON over SECURE/LOOPBACK, Cache-Control:no-store; secret-bearing
request/response bodies are excluded from logs, analytics, exports and evidence.

| Operation | Request | Success |
| --- | --- | --- |
| POST `/api/v1/auth/login` | `{login,password}` + installation header + CSRF | 200 AuthContext, new opaque cookie/logical session |
| POST `/api/v1/auth/renew` | `{expectedSessionId:UUID}` + all expected headers + CSRF | 200 AuthContext, same logical session, persisted activity/cookie extension |
| POST `/api/v1/auth/logout` | `{expectedSessionId:UUID}` + all expected headers + CSRF | 200 LogoutResult |
| POST `/api/v1/auth/password` | `{currentPassword,newPassword}` + all headers + CSRF | 200 AuthContext; other sessions revoked, current retained |
| POST `/api/v1/auth/reauth` | `{password}` + all headers + CSRF | 200 `{reauthenticatedUntil:Instant}` |
| GET `/api/v1/accounts?page=0&size=20` | ADMIN, normal existing page convention | 200 page<AccountView> |
| POST `/api/v1/accounts` | ADMIN+step-up, `{login,displayName,role}` | 201 `{account:AccountView,temporaryPassword:string}` once |
| PATCH `/api/v1/accounts/{id}` | ADMIN+step-up, any of `{login,displayName,role,status}` | 200 AccountView |
| POST `/api/v1/accounts/{id}/password-reset` | ADMIN+step-up, `{}` | 200 `{account:AccountView,temporaryPassword:string}` once |

Renew is triggered only by observed user interaction or active protected work,
not polling, SSE, timers alone or background retry. Use server time and monotonic
activity updates; concurrent requests cannot move activity backwards. Web
coalesces to at most one renewal per minute while interaction is pending; normal
authorized mutations also count as activity. A true 14-day idle session cannot
renew itself back to life. No short absolute active-game cutoff is introduced.

LogoutResult = `{sessionId:UUID, status:'REVOKED'|'ALREADY_INACTIVE'}` only when
the original cookie proves that exact logical session (including a retained
revocation tombstone). A different current cookie/session returns auth mismatch,
does not delete/revoke that newer session and does not confirm old revocation.
No-cookie/unknown-token failure must not claim a specific old session was revoked.
Avoid a stale logout response clearing a newer shared cookie; revoking the opaque
credential server-side is mandatory, blind shared-cookie clearing is not.

Login atomically revokes a recognized prior cookie session and exposes that ID as
`replacedSessionId` (otherwise null); this is a nonsecret acknowledgement, not a
way to revoke an arbitrary supplied UUID. A pending logout intent contains only
original installation/account/session IDs, no credential. Web drains it before
new login when the original cookie is still available, and never sends it as
logout of whichever session happens to be current later.

Reauth freshness is **300 seconds**, server time. Login/own-password verification
may establish it; it never bypasses temporary-password restrictions. Admin create,
role/block/reset changes require it. Own password change revokes every OTHER
session; block/reset and role change revoke all target sessions immediately.
Login/display rename preserves identity and does not transfer queue ownership.
The last active administrator guard is transactional under concurrent requests.

Limits: login 1–64 ASCII `[A-Za-z0-9._-]`, trim outer whitespace and normalize
case with Locale.ROOT; displayName 1–120 Unicode code points after trim. Password
8–1024 Unicode code points, spaces retained, no composition rule or truncation;
reject malformed Unicode. Argon2id via Spring PasswordEncoder is the selected
hash family: minimum memory19456KiB, iterations2, parallelism1, salt16bytes,
hash32bytes. Compatible patched framework/BouncyCastle versions are pinned in the
core plan and dependency report; do not add an unrelated major upgrade.
Temporary secrets are generated server-side and delivered once over private TLS
or owner console; no default secret. Local owner CLI bootstrap/recovery reads
hidden console/explicit stdin, never secret command arguments or normal logs.

Baseline rate policy: login 5/min per (verified source,normalized login), 20/min
per verified source, no account-wide permanent lockout. Reauth 5/5min per session;
renew 60/min per session. 429 includes Retry-After. Bound storage and cleanup of
rate/audit state; private security audit retention 30 days, configurable locally.
Security audit contains actor/target IDs, timestamp/action/result, never secrets.
Expected audit/rate changes on denial are not changes to sports/domain state.

## Error and uncertain-outcome semantics

RFC9457 ProblemDetail keeps the existing `type,title,status,detail,instance` and
machine `code`. Pre-controller auth/CSRF/transport/context denial also carries
`requestRejectedBeforeExecution:true`; this refers ONLY to this HTTP attempt.
It does not prove an earlier lost-response request with the same intent failed.

| Status | Codes |
| --- | --- |
| 401 | AUTH_REQUIRED, AUTH_CREDENTIALS_INVALID, AUTH_SESSION_INVALID, AUTH_SESSION_EXPIRED, AUTH_SESSION_REVOKED |
| 403 | PUBLIC_TRANSPORT_READ_ONLY, AUTH_CONTEXT_REQUIRED, AUTH_IDENTITY_MISMATCH, AUTH_ACCOUNT_BLOCKED, AUTH_PASSWORD_CHANGE_REQUIRED, AUTH_PERMISSION_DENIED, AUTH_REAUTH_REQUIRED, CSRF_INVALID |
| 409 | ACCOUNT_LOGIN_TAKEN, LAST_ADMIN_REQUIRED; existing SPORTS domain409 unchanged |
| 429 | AUTH_RATE_LIMITED, with Retry-After |

Login failure is generic AUTH_CREDENTIALS_INVALID for unknown login/wrong password/
blocked account. Validation400 uses the existing field-error convention, no raw
password echo. Auth denials neither silently replay unsafe operations nor mutate
event receipts, local frozen intent or canonical log. Web distinguishes auth from
domain rejection even after an uncertain write; reauthenticate the original
owner, then reconcile outcomes using the existing canonical/receipt protocol.
Auth failures must not set a stale cookie-clearing header that removes a newer
login. No API auth error redirects to HTML; public entry login navigation is UI.

## Final permission and delivery scope

Public: sports/reference/photo/roster/match all statuses/score/events/live/OBS
sources; statistics and CSV/ZIP only FINISHED. Organizer: protected sports writes,
all current statistics, portable JSON, OBS admin/settings/preview and API docs.
Admin: organizer plus account administration. The complete method/path/alias
inventory and tests are owned by #153; permissive missing-match defaults forbidden.
Backend OBS admin and Swagger get the same secure session/CSRF/context adapter;
Browser Sources remain anonymous, without embedding staff credentials.

#151 manual immutable promotion must land before auth product source merges.
No intermediate merge updates human runtimes or enables/dispatches CD. #154
proves synthetic 247/V9→71/V10→auth, data/photo/profile/receipt preservation,
TLS/readiness/startup and maintenance-contained rollback. Before auth/origin flip,
owner verifies old device queues and syncs; known pending/uncertain => defer.
No post-upgrade anonymous queue adoption or browser-storage deletion.

Head/product children stay OPEN until shared Backend+Web QA and PO. Technical
callbacks for each available slice go immediately after actual merge/checks/target
to Web dependent issues and Flutter owner (until assigned: PO), not after general
QA. Flutter #4/#5 are future consumers only; no Flutter implementation/outbox claim.

References: [Spring CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html),
[Spring PasswordEncoder](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/password-encoder.html),
[OWASP sessions](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html),
[OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
