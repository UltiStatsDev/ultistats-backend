# Approved point-completion history

Use `GET /api/v3/matches/{matchId}/events` for the complete ordered history. The endpoint uses the same public sports-read policy as the v1/v2 journals and the same `X-UltiStats-Point-Lineups` capability header. A history with a `POINT_RESULT` requires capability 4.

Feature discovery is available on `GET /api/v1/matches/{matchId}/point-lineups`. `pointCompletionHistoryReadSupported: true` advertises the v3 endpoint. `pointCompletionApprovalWriteEnabled: false` indicates that this server has no approval command. Draft save availability still follows `pointResultsWriteEnabled`.

The response contains `entries` in display order. Each entry has a one-based `position` in the returned history and a `kind`.

| Kind | Fields |
| --- | --- |
| `ORIGINAL` | `event` is the existing event response, including UUID, append `sequenceNumber` and exact `occurredAt`. A `POINT_RESULT` still belongs to the point it closes and its UUID still opens the next point. |
| `RESTORED` | `pointId`, `closingEventId` and `insertion` identify a restored action tied to the original point and marker. The insertion contains its own UUID, action type, actor references, `afterEventId` and `elapsedOffsetMs`. Null elapsed time stays explicit and zero means match start. The insertion carries no append sequence or absolute timestamp. |

A null anchor places the restored action at the start of its point. Other anchors identify active original events before the point's closing marker. Actions sharing an anchor are ordered as stored in the approved snapshot. A restored personal goal or Callahan is an attribution for the recorded result. Use the canonical marker for the team score and point boundary. Drafts are accessible through the staff-only draft endpoint.

The top-level `lastSequenceNumber` and `logRevision` are the append synchronization cursor. They retain their v2 meanings, including deleted events in `lastSequenceNumber`. Display positions may change once a completion is approved and cannot be used as append cursors. `GET /api/v2/matches/{matchId}/events?view=append` returns the original v2 cursor and active append rows. It labels the response with `X-UltiStats-Event-View: append`.

For a match with approved rows, the default v1 event collection and v2 journal return 409 `POINT_COMPLETION_HISTORY_REQUIRED` after existing client capability checks pass. An invalid approved snapshot returns 409 `POINT_COMPLETION_HISTORY_CONFLICT` on v3. A missing match returns 404. With no approved rows, v1 and default v2 retain their usual behavior. Individual v1 original-event lookups keep their existing contract.
