# Auth153 test matrix v1

Status: **PREPARED / NOT_RUN**. Baseline `5c9781f2895141ca7a430588873c764d8c1e2dd6`.
Contract candidate1.0.0; final code binding awaits frozen wire and152 chain hook.
These are expected oracles, not executed outcomes. Public means no private cookie parsing.
Public transport denies private routes before credential/body processing. Authenticated
private reads validate expected identity whenever any expected header is supplied.

## Method/path cases

Each row is parameterized on ANONYMOUS and SECURE/LOOPBACK entry modes, GET→HEAD
where supported, guest, organizer, admin, temporary password, expired and revoked.
Allowed mutations below presume valid CSRF, all captured UUIDs and a valid fixture;
“Allowed” means reaches the existing domain behavior, not that every invalid body succeeds.

| ID | Method | Path | Guest | Organizer/admin | Temporary | Expected domain effect on auth denial | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S01 | GET | `/api/v2/matches/{matchId}/events` | Public | Public | Public | None | NOT_RUN |
| S02 | POST | `/api/v2/matches/{matchId}/events/batch` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S03 | GET | `/api/v1/matches/{matchId}/events` | Public | Public | Public | None | NOT_RUN |
| S04 | GET | `/api/v1/matches/{matchId}/events/{eventId}` | Public | Public | Public | None | NOT_RUN |
| S05 | POST | `/api/v1/matches/{matchId}/events` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S06 | PATCH | `/api/v1/matches/{matchId}/events/{eventId}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S07 | POST | `/api/v1/matches/{matchId}/events/undo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S08 | DELETE | `/api/v1/matches/{matchId}/events/{eventId}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S09 | GET | `/api/v1/matches` | Public | Public | Public | None | NOT_RUN |
| S10 | GET | `/api/v1/matches/{id}` | Public | Public | Public | None | NOT_RUN |
| S11 | POST | `/api/v1/matches` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S12 | PUT | `/api/v1/matches/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S13 | PATCH | `/api/v1/matches/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S14 | DELETE | `/api/v1/matches/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S15 | POST | `/api/v1/matches/{id}/start` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S16 | POST | `/api/v1/matches/{id}/end` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S17 | GET | `/api/v1/matches/{matchId}/events/stream` | Public | Public | Public | None | NOT_RUN |
| S18 | POST | `/api/v1/matches/export` | 401 AUTH_REQUIRED | TRANSFER | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S19 | POST | `/api/v1/matches/import` | 401 AUTH_REQUIRED | TRANSFER | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S20 | GET | `/api/v1/players` | Public | Public | Public | None | NOT_RUN |
| S21 | GET | `/api/v1/players/{id}` | Public | Public | Public | None | NOT_RUN |
| S22 | GET | `/api/v1/players/{playerId}/teams` | Public | Public | Public | None | NOT_RUN |
| S23 | POST | `/api/v1/players` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S24 | PUT | `/api/v1/players/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S25 | PATCH | `/api/v1/players/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S26 | DELETE | `/api/v1/players/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S27 | PUT | `/api/v1/players/{playerId}/photo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S28 | GET | `/api/v1/players/{playerId}/photo` | Public | Public | Public | None | NOT_RUN |
| S29 | DELETE | `/api/v1/players/{playerId}/photo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S30 | POST | `/api/v1/players/{playerId}/uploadPhoto` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S31 | GET | `/api/v1/players/{playerId}/photoUrl` | Public | Public | Public | None | NOT_RUN |
| S32 | DELETE | `/api/v1/players/{playerId}/photoUrl` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S33 | GET | `/api/v1/matches/{matchId}/statistics` | FINISHED only | All statuses | FINISHED only | None | NOT_RUN |
| S34 | GET | `/api/v1/matches/{matchId}/statistics/export` | FINISHED only | All statuses | FINISHED only | None | NOT_RUN |
| S35 | GET | `/api/v1/teams` | Public | Public | Public | None | NOT_RUN |
| S36 | GET | `/api/v1/teams/{id}` | Public | Public | Public | None | NOT_RUN |
| S37 | POST | `/api/v1/teams` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S38 | PUT | `/api/v1/teams/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S39 | PATCH | `/api/v1/teams/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S40 | DELETE | `/api/v1/teams/{id}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S41 | GET | `/api/v1/teams/{teamId}/players` | Public | Public | Public | None | NOT_RUN |
| S42 | PUT | `/api/v1/teams/{teamId}/players/{playerId}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S43 | DELETE | `/api/v1/teams/{teamId}/players/{playerId}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S44 | PUT | `/api/v1/teams/{teamId}/photo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S45 | GET | `/api/v1/teams/{teamId}/photo` | Public | Public | Public | None | NOT_RUN |
| S46 | DELETE | `/api/v1/teams/{teamId}/photo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S47 | POST | `/api/v1/teams/{teamId}/uploadPhoto` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S48 | GET | `/api/v1/teams/{teamId}/photoUrl` | Public | Public | Public | None | NOT_RUN |
| S49 | DELETE | `/api/v1/teams/{teamId}/photoUrl` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S50 | POST | `/api/v2/matches/{matchId}/events/undo` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S51 | GET | `/api/v2/matches/{matchId}/events/undo/{commandId}` | 401 AUTH_REQUIRED | SPORTS_WRITE | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S52 | GET | `/api/v1/obs/config` | Public | Public | Public | None | NOT_RUN |
| S53 | PUT | `/api/v1/obs/config` | 401 AUTH_REQUIRED | OBS_ADMIN | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |
| S54 | GET | `/api/v1/obs/config/stream` | Public | Public | Public | None | NOT_RUN |
| S55 | GET | `/api/v1/obs/stream` | Public | Public | Public | None | NOT_RUN |
| S56 | GET | `/api/v1/obs/scoreboard/{matchId}` | Public | Public | Public | None | NOT_RUN |
| S57 | PUT | `/api/v1/obs/scoreboard/{matchId}/profile` | 401 AUTH_REQUIRED | OBS_ADMIN | 403 AUTH_PASSWORD_CHANGE_REQUIRED | None | NOT_RUN |

## Static and generated surfaces

| ID | Surface | Expected rule | Result |
| --- | --- | --- | --- |
| R01 | `/obs/admin.mjs` | OBS_ADMIN | NOT_RUN |
| R02 | `/obs/client.mjs` | PUBLIC | NOT_RUN |
| R03 | `/obs/colors.mjs` | PUBLIC | NOT_RUN |
| R04 | `/obs/feed.mjs` | PUBLIC | NOT_RUN |
| R05 | `/obs/obs.css` | PUBLIC | NOT_RUN |
| R06 | `/obs/overlay.mjs` | PUBLIC | NOT_RUN |
| R07 | `/obs/renderer.mjs` | PUBLIC | NOT_RUN |
| R08 | `/obs/scoreboard-admin.mjs` | OBS_ADMIN | NOT_RUN |
| R09 | `/obs/scoreboard-client.mjs` | PUBLIC | NOT_RUN |
| R10 | `/obs/scoreboard-model.mjs` | PUBLIC | NOT_RUN |
| R11 | `/obs/scoreboard-renderer.mjs` | PUBLIC | NOT_RUN |
| R12 | `/obs/scoreboard.css` | PUBLIC | NOT_RUN |
| R13 | `/obs/scoreboard.mjs` | PUBLIC | NOT_RUN |
| R14 | `/obs/shared-worker.js` | PUBLIC | NOT_RUN |
| R15 | `/obs/transport.mjs` | PUBLIC | NOT_RUN |
| R16 | `/obs-admin.html` | OBS_ADMIN | NOT_RUN |
| R17 | `/obs-battle-log.html` | PUBLIC except any `preview=1` → OBS_ADMIN | NOT_RUN |
| R18 | `/obs-diagnostic.html` | PUBLIC | NOT_RUN |
| R19 | `/obs-scoreboard.html` | PUBLIC except any `preview=1` → OBS_ADMIN | NOT_RUN |
| R20 | `/uploads/**` | Public GET/HEAD, writes never static | NOT_RUN |
| R21 | `/swagger-ui.html`, `/swagger-ui/**` | API_DOCS for GET/HEAD incl initializer/assets | NOT_RUN |
| R22 | `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml` | API_DOCS incl swagger-config | NOT_RUN |
| R23 | `/api/v1/auth/**`, `/api/v1/accounts/**` | Core152 matrix; anonymous connector403 before parse | NOT_RUN |
| R24 | `/api/v1/installation`, `/health/ready` | Public, no private fields, no activity | NOT_RUN |

## Negative and effect cases

Apply N01–N11 to **every protected unsafe S row**; apply N12 to every private read.
Every denial checks code/status, RFC9457 JSON, no HTML redirect,
`requestRejectedBeforeExecution:true`, no stale cookie clearing and no sports effects.

| ID | Input/scenario | Oracle | Owner/test | Result |
| --- | --- | --- | --- | --- |
| N01 | No cookie plus complete or absent expected capture | 401 AUTH_REQUIRED on private entry; anonymous entry403 transport | 153/152 identity | NOT_RUN |
| N02 | Valid cookie, all expected headers absent | 403 AUTH_CONTEXT_REQUIRED | 153 identity | NOT_RUN |
| N03 | Valid cookie, each of three headers omitted in turn | 403 AUTH_CONTEXT_REQUIRED | 153 identity | NOT_RUN |
| N04 | Installation UUID individually mismatched | 403 AUTH_IDENTITY_MISMATCH | 153 identity | NOT_RUN |
| N05 | Account UUID individually mismatched | 403 AUTH_IDENTITY_MISMATCH | 153 identity | NOT_RUN |
| N06 | Logical session UUID individually mismatched incl same account new login | 403 AUTH_IDENTITY_MISMATCH | 153 identity | NOT_RUN |
| N07 | Malformed/noncanonical/duplicate expected headers | Fail closed before controller; exact152 denial convention recorded at binding | 153/152 identity | NOT_RUN |
| N08 | Missing/wrong CSRF, correct expected capture | 403 CSRF_INVALID, no body effects | 152 CSRF /153 matrix | NOT_RUN |
| N09 | Expired/revoked/blocked session | AUTH_SESSION_EXPIRED401 / AUTH_SESSION_REVOKED401 / AUTH_ACCOUNT_BLOCKED403 | 152 authority /153 matrix | NOT_RUN |
| N10 | Anonymous transport forged Cookie/Authorization/Host/Origin/forwarding | 403 PUBLIC_TRANSPORT_READ_ONLY before auth parsing | 154 connector /153 matrix | NOT_RUN |
| N11 | Invalid JSON, oversized/malformed multipart with auth denied | Auth/transport barrier before binding/storage; fingerprint unchanged | 153 identity | NOT_RUN |
| N12 | Any expected header on private read | All3required/matching; mismatched403, no payload/cookie refresh | 153/152 identity | NOT_RUN |
| N13 | HEAD/OPTIONS/TRACE/wrong verb/unknown/trailing slash/case/matrix/encoded paths | Explicit read policy or safe404/405/firewall deny; no implicit grant, no domain effects | 153 matrix | NOT_RUN |
| N14 | PLANNED/IN_PROGRESS/FINISHED/deleted/missing statistics fixture JSON+HEAD+ZIP | Only FINISHED public, no missing default, staff existing404 unchanged | 153 statistics | NOT_RUN |
| N15 | ZIP Accept/query variations; independent CSV routes if discovered | Same gate; invented nonexistent CSV remains404 | 153 statistics | NOT_RUN |
| N16 | Known committed batch response ignored, then original frozen retry denied | Earlier canonical event/score/revision unchanged, no new receipt/replay claim | 153 uncertain outcome | NOT_RUN |
| N17 | Known committed APPLIED/REJECTED undo response ignored, then denied retry | Original immutable receipt unchanged; denial only current attempt | 153 uncertain outcome | NOT_RUN |
| N18 | Unknown command denied before execution then receipt lookup | No receipt reserved; unknown404 does not prove earlier in-flight failure | 153 uncertain outcome | NOT_RUN |
| N19 | Reset/block/demotion races with domain write | Singleton boundary serializes fresh permission through effects; no stale-authority write | 152 boundary /153 mutation | NOT_RUN |
| N20 | Passive public GET/SSE with valid/expired/forged cookie | Same public payload, no cookie parse/auth refresh/activity | 153/152/154 | NOT_RUN |
| N21 | Malicious unconfigured Origin/Referer and credentialed preflight | Configured-origin policy denies; no wildcard credentials | 153 CORS /154 config | NOT_RUN |
| N22 | OBS draft captured before cookie/session/account switch | Expected identity denies; preserve draft, no new-owner automatic unsafe retry | 153 JS/HTTP | NOT_RUN |
| N23 | Swagger unsafe try-it-out and private read | In-memory CSRF/capture headers, same-origin only, no retry under another identity | 153 JS/HTTP | NOT_RUN |
| N24 | Public OBS source native import graph + actual SSE payload | No staff adapter/secret URLs, public data/styles only; no auth cookie required | 153 regression + QA native | NOT_RUN |
| N25 | Temporary password or organizer hits account admin without stepup | Temporary/permission denied; ADMIN sensitive operations require fresh reauth | 152 core /153 integration | NOT_RUN |

Denied-effect manifest: teams/players/memberships/matches (incl deleted), active/deleted
events, score, sequence high-water, logRevision, batch receipts, undo receipts,
global/per-match OBS settings, upload file hashes and after-commit publication count.
Private rate/audit bookkeeping is allowed by contract and recorded separately.

Existing QA A01–A03/A05–A06/A08/A10–A14/A19–A24 are mapped here; this worker matrix
never marks general QA rows PASS. Core lifecycle/admin and actual ingress/client
acceptance retain their separate owners.
