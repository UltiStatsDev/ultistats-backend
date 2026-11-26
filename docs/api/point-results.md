# Quick point results

Public GET `/api/v1/matches/{matchId}/point-lineups` advertises two independent booleans. Choose `X-UltiStats-Point-Lineups: 4` when `pointResultsReadSupported` is true. With a missing or false read field, negotiate capabilities 1–3 using existing discovery. `pointResultsWriteEnabled` reports `ULTISTATS_POINT_RESULTS_WRITE_ENABLED`, which defaults to false. A server with its writer off still serves the full capability 4 journal. An older client reading a journal with an active point result receives 409 `POINT_RESULT_CLIENT_REQUIRED`.

Use PUT `/api/v1/matches/{matchId}/point-lineups/{pointId}/result` with `SPORTS_WRITE`, CSRF and capability 4. Pass the current point ID from discovery, the current log position from GET `/api/v2/matches/{matchId}/events`, and a new client operation UUID. Keep all four fields and the path unchanged for an exact retry.

```json
{
  "operationId": "3450465b-7376-4d3f-ae96-a98d493ff26f",
  "scoringTeamId": "e944328b-7621-463c-836f-f4955c29ac10",
  "expectedLogRevision": 5,
  "expectedLastSequenceNumber": 7
}
```

A fresh command requires an in-progress match and the current unpaused point. A point can close before PULL with or without a confirmed lineup, or after partially recorded play. Timeouts, halftime and open announcements must end through their own commands. Both cursors must match the journal under the same match row lock as gameplay writes. The server returns 409 `EVENT_LOG_STALE` for a changed cursor, `POINT_RESULT_CONFLICT` for a stale point ID or open standalone announcement, and the existing lifecycle, timestamp or sequence conflict for those failures. An invalid request or scoring team outside the match returns 400.

The 200 response contains `replayed`, the canonical team-only `marker`, the closed `pointId`, the marker-derived `nextPointId`, and `log` containing the full active `events` array, `lastSequenceNumber` and `logRevision`. The marker's `id` equals `operationId`. Its `occurredAt` is the server's exact registration time. It carries no measured action time or player attribution. If registration would precede the match start or any active event, the server returns `EVENT_BEFORE_START` or `EVENT_OUT_OF_ORDER`. On a fresh success the successor is eligible for the usual lineup confirmation, using the new log revision.

An exact retry returns `replayed: true`, the same marker and stable successor ID, and the current journal. A later retry can name a successor that has since started, closed, or ceased to be open after match finish. Use discovery for the current point. Replay still requires authorization, CSRF and capability 4 when the writer is off. Reusing an operation UUID with another payload, a deleted marker or a marker that no longer closes the given point returns 409 `EVENT_IDEMPOTENCY_CONFLICT`. Generic event creation, edits and undo cannot create, alter or retract this marker. Export and import retain the existing typed portable format 7 and independent write gates.
