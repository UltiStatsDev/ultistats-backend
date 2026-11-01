# Auth enforcement surface inventory v1

Source baseline: `5c9781f2895141ca7a430588873c764d8c1e2dd6`.
Policy target: auth-v1 candidate1.0.0; implementation awaits mutual freeze and
published core interfaces. This inventory is preparation, not an access-control PASS.

`auth-surfaces-v1.json` enumerates all **57** actual application method/path
mappings and **19** packaged static files with source locations. Regenerate with
`python3 tools/auth153/inventory.py`, review the diff, then use `--check` to detect
drift. The scanner covers the checked baseline's literal Kotlin mapping style;
it is not an authorization implementation. Runtime RequestMappingHandlerMapping
coverage must also catch framework and future nonliteral mappings.

## Policy and identity rules

| Policy | Anonymous transport | Secure/loopback guest | Organizer/admin | Temporary password |
| --- | --- | --- | --- | --- |
| PUBLIC | Read | Read | Same public representation | Read |
| FINISHED_OR_STATISTICS_READ_ALL | FINISHED only | FINISHED only | All existing statuses | FINISHED only |
| SPORTS_WRITE | 403 transport | 401 auth | Allowed | 403 password change |
| TRANSFER | 403 transport | 401 auth | Allowed | 403 password change |
| OBS_ADMIN | 403 transport | 401 auth | Allowed | 403 password change |
| API_DOCS | 403 transport | 401 auth | Allowed | 403 password change |
| ACCOUNT_ADMIN | 403 transport | 401 auth | Admin only; mutations step-up | 403 password change |

Protected unsafe methods require CSRF and exact captured installation/account/
logical-session headers before controller binding or side effects. Private reads
validate all three whenever any capture header is supplied. Missing capture is
403 AUTH_CONTEXT_REQUIRED; mismatch403 AUTH_IDENTITY_MISMATCH; no session401
AUTH_REQUIRED. Temporary restriction cannot be bypassed by fresh reauth. Ordinary
organizer sports/transfer/OBS/docs do not require admin step-up. Account changes
and auth endpoints belong to152, using the same security chain.

Anonymous transport denies private paths and unsafe methods **before** credential
inspection, CSRF, JSON/multipart parsing, controller/domain access. Forged Cookie,
Authorization, Host, Origin, Referer and forwarding headers never upgrade entry
mode. Public payloads never acquire account/private fields through cookies.

## Application families and unusual cases

- Teams: collection/detail CRUD, membership PUT/DELETE; photo GET/PUT/DELETE and
  legacy POST uploadPhoto, GET/DELETE photoUrl. Players have collection/detail
  CRUD, teams GET and the same six photo methods. Uploads are sports mutations.
- Matches: collection/detail CRUD, POST start/end; all statuses public for reads.
- Events v1: list/detail GET, create POST, PATCH/DELETE event, POST bodyless undo.
  v2: canonical log GET, batch POST, command undo POST, receipt GET.
  Receipt GET is private SPORTS_WRITE, although it changes nothing.
- SSE: match events `/api/v1/matches/{matchId}/events/stream`, OBS config
  `/api/v1/obs/config/stream`, shared `/api/v1/obs/stream` all public. No passive
  session renewal; public shared messages remain only committed public signals.
- Statistics: JSON GET and export GET share FINISHED gating. Export is one ZIP
  containing CSV entries, not an independent `/csv` route. Query parameters and
  Accept headers cannot bypass the same policy. Missing/soft-deleted match is
  never treated as FINISHED; retain existing404 response after safe lookup.
- Portable JSON: POST `/api/v1/matches/export` remains private TRANSFER even
  for finished-only selections; POST import is also TRANSFER. Both are protected
  unsafe requests, including export; never infer permission from a read-like name.
- OBS config GET and scoreboard GET expose effective public styling required by
  native sources. PUT global config and PUT per-match profile require OBS_ADMIN.
  Published profile data is public; admin page, preview mode and editor modules
  require staff. Source URLs carry only matchId, no cookies/tokens in URL.

## Static, generated and transport surfaces

| Surface | Read policy | Notes |
| --- | --- | --- |
| `/uploads/**` | PUBLIC | WebConfig file resource handler; GET/HEAD only; write routes are controllers |
| `/obs-admin.html`, `/obs/admin.mjs`, `/obs/scoreboard-admin.mjs` | OBS_ADMIN | Secure context/CSRF adapter used by both editors |
| `/obs-battle-log.html`, `/obs-scoreboard.html` | PUBLIC | `preview=1` changes both to OBS_ADMIN; any duplicate preview parameter requesting1 is private |
| `/obs-diagnostic.html` | PUBLIC | Existing diagnostic contains public match/event reads only; no secrets |
| Remaining `/obs/*` assets | PUBLIC | Explicit packaged resource list in JSON, no unrestricted private-prefix permit |
| `/swagger-ui.html`, `/swagger-ui/**` | API_DOCS | Includes redirect target and assets; no unauthenticated HTML login redirect |
| `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml` | API_DOCS | Includes swagger-config and alternate representation |
| `/api/v1/installation`, `/health/ready` | PUBLIC | New154/152 ownership; readiness minimal, no bootstrap/admin-count leak |
| `/api/v1/auth/**`, `/api/v1/accounts/**` | Private core152 | Never available over anonymous connector;152 inventory supplies exact operations |
| OPTIONS | Configured CORS preflight only | Never invoke domain operation; reject unapproved credentialed origins |
| HEAD | Same policy as GET | Particularly statistics/receipt/docs/static/preview; no HEAD loophole |
| PUT/PATCH/POST/DELETE or unsupported methods on read/static routes | Deny before effects | Public anonymous connector cannot reach them; private unknown methods fail closed |
| Unknown paths, `/error`, dispatcher ERROR/ASYNC, trailing slash, encoded path, semicolon/matrix path, case variants | No implicit public grant | Preserve safe404/405/firewall rejection; async SSE redispatch must not reset identity or renew |

Framework defaults (including welcome/favicon/error) are verified against actual
runtime handler mappings; no broad `GET /api/** permitAll`. New controller routes
must produce a mapping-coverage test failure until explicitly classified.

## Side effects and uncertain outcomes

For every denied mutation, compare synthetic DB rows, active/deleted event JSON,
score, sequence high-water mark, logRevision, batch/undo receipts, OBS settings,
file hashes and publication counts. Audit/rate bookkeeping is separately allowed
by contract; no denied domain request reserves a commandId or changes a receipt.
`requestRejectedBeforeExecution:true` describes this attempt only. After a prior
committed lost-response request, auth denial cannot overwrite the receipt or imply
the earlier request failed; preserve frozen UUID/time/base/payload and canonical
reconciliation. Unknown receipt404 remains unknown, not rejection or replay proof.

## Verification traceability (all NOT_RUN)

| Matrix rows | Planned evidence |
| --- | --- |
| A01/A02 | AuthSurfaceMatrixTest, full handler coverage, FINISHED JSON/HEAD/ZIP checks |
| A03/A19 | Public connector spoof/method tests coordinated with154; CSRF/CORS negative cases |
| A05/A06 | Temporary permissions and admin separation/step-up integrated with152 |
| A08/A10/A11/A13/A14 | AuthExpectedIdentityTest; idle/activity and stale session guards from152; prior committed undo/batch readback |
| A20/A21/A22/A23 | Secure OBS/Swagger adapter JS tests; public source regression; safe URL/no-secret checks |
| A24 | Exact source/build/CI/review/runtime packet and owned-resource cleanup receipt |

QA's separate24-row acceptance matrix remains PREPARED/NOT_RUN. Worker tests map
to it and do not substitute for combined Backend/Web/native OBS acceptance.
