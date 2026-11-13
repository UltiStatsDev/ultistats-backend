# Exact OBS scoreboard source keys

Staff with `OBS_ADMIN` can manage keys through the existing staff session.

| Method and path | Result |
| --- | --- |
| `POST /api/v1/obs/source-keys` | Send `{"matchId":"<uuid>"}`. Returns 201 with key metadata and `secret`. |
| `GET /api/v1/obs/source-keys` | Returns key metadata, newest first. |
| `DELETE /api/v1/obs/source-keys/{keyId}` | Returns 204 for a known key, including a key already revoked. Unknown IDs return 404. |

Issue and revoke require the usual CSRF token and captured installation, account
and logical session IDs. Metadata contains `keyId`, `matchId`, `scope`
(`EXACT_SCOREBOARD`), `createdAt`, and `revokedAt` when revoked. Only the
issue response contains the secret. If it is lost, issue another key and revoke
the lost key if it should no longer work. Multiple keys can be active together.
Keys and revocation state are stored with the installation database.

Use `GET /api/v1/obs/sources/scoreboard/{matchId}` on the configured HTTPS
origin with `Authorization: Bearer <secret>`. Send the secret only in this
header. The response is the existing scoreboard snapshot for the bound match,
including planned matches. Authentication for this route uses the bearer
header alone.

| Status | Meaning |
| --- | --- |
| 400 | The path ID is malformed. |
| 401 | The key is missing, invalid or revoked. The response includes `WWW-Authenticate: Bearer`. |
| 403 | The key is bound to another match or the request arrived outside secure ingress. |
| 404 | The key matches the requested ID and that match has been removed. |

The new endpoints return `Cache-Control: no-store`. A request whose database
snapshot predates a revocation may finish using that snapshot. Subsequent
requests after the revocation commits receive 401.
