# Exact scoreboard links

Staff with OBS_ADMIN on HTTPS can copy a link through
`GET /api/v1/obs/source-keys/current/{matchId}`. The no-store response includes
`status`, `key` and `url`. `ACTIVE` includes both key metadata and a full URL.
`REISSUE_REQUIRED` includes legacy key metadata and a null URL.
`NO_ACTIVE_LINK` returns null for both. The URL uses the configured HTTPS
secure origin and the reserved path
`/obs-sources/scoreboard.html?matchId=<uuid>#key=<bearer>`. The protected Browser
Source page is a separate delivery. Integrations can verify the key with
`GET /api/v1/obs/sources/scoreboard/{matchId}` and an Authorization Bearer header.

`POST /api/v1/obs/source-keys` requires UUIDs `matchId` and `operationId`, the
staff session, CSRF and captured installation, account and session IDs. Success
returns the same ACTIVE response with 201. Keep the operation ID for retries of
the same request. A retry returns that link while current. A retired or
differently scoped ID returns 409. A failed rotation leaves the previous key
active. The list still provides metadata only. DELETE by key UUID is safe to
repeat and affects only that key.

## V18 preflight

Before upgrading a V17 database, run this read-only query. A returned row means
an operator must choose which link to retain for that scope and explicitly
revoke the others using the V17 staff API before upgrading.

```sql
BEGIN READ ONLY;
SELECT scope, match_id, count(*) AS active_count,
       array_agg(key_id ORDER BY created_at, key_id) AS active_key_ids
FROM obs_source_keys
WHERE revoked_at IS NULL
GROUP BY scope, match_id
HAVING count(*) > 1;
COMMIT;
```

V18 enforces this check transactionally through a unique partial index.
Conflicting rows abort V18, leaving the V17 schema, key history and bearer
access intact. A single legacy key keeps reading until explicit rotation or
revocation.

## Installation key and recovery

Configure `APP_OBS_SOURCE_KEY_FILE` as the path to a mounted read-only file with
exactly 32 independently random bytes in binary form. Reuse the same file
across instances and restarts. Back it up separately from the database.
V18 pins its installation-specific HMAC fingerprint on the first committed
rotation. The database stores the fingerprint and bearer digests.
A missing, malformed or different file returns 503 when copying an ACTIVE link
or rotating. Bearer reads and explicit revocation remain available.

Recover against the same database with a V18-compatible backend and the same
key file. Keep issuance closed during recovery and avoid reverting to the V17
writer or restoring revoked keys from an older database snapshot. If the file is
lost, plan an explicit key-material replacement and link reissue.
