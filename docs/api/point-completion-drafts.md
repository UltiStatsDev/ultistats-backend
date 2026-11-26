# Point completion drafts

Public point lineup discovery exposes `pointCompletionDraftsReadSupported`. A missing field means the endpoint is unavailable. Use `pointResultsWriteEnabled` to decide whether this server accepts saves. Both operations require `X-UltiStats-Point-Lineups: 4` and `SPORTS_WRITE`. PUT also requires CSRF.

GET and PUT `/api/v1/matches/{matchId}/point-lineups/{pointId}/completion-draft` apply to a finished match's canonical point closed by an active `POINT_RESULT`. The response includes the `matchId`, `pointId`, canonical team `marker`, current `logRevision`, nullable `lineupRevision`, and `draftRevision`. For a point without a saved draft, `draftRevision` is 0 and `draft` is null. Otherwise `draft` contains `closingEventId`, `baseLogRevision`, nullable `baseLineupRevision`, and the saved `insertions`.

Send a full replacement candidate with the revisions just read. Use explicit null for `expectedLineupRevision` when the point has no confirmed lineup.

```json
{
  "expectedClosingEventId": "8e1ebcbf-eb37-423b-8a0f-ec729569fe0d",
  "expectedLogRevision": 5,
  "expectedLineupRevision": null,
  "expectedDraftRevision": 0,
  "insertions": [
    {
      "id": "06cdcd73-337b-4418-b9c4-e6350963b42c",
      "afterEventId": null,
      "type": "PULL",
      "fromActorId": "edc6026a-7a06-4942-9d0c-ab9226d79a9c",
      "toActorId": null,
      "elapsedOffsetMs": 0
    }
  ]
}
```

Keep each client-generated insertion UUID when editing that action. The list accepts at most 500 insertions with distinct IDs unused by the journal. A null anchor inserts at the beginning. Other anchors must be active original events of this point before the closing marker. Insertions with the same anchor appear in request array order. The preview returns each row as either `{"original": ...}` with the source ID, sequence and exact timestamp or `{"insertion": ...}` with the saved insertion. The canonical marker is returned separately.

The mock's pull, brick, pickup, pass, turnover and goal use `PULL`, `BRICK`, `PICKUP`, `PASS`, `INCOMPLETE_PASS` and `GOAL`. The draft also accepts the existing `BLOCK`, `BLOCK_MARKER`, `BLOCK_FIELD`, `INTERCEPTION`, `CALLAHAN`, `PLAYER_SUBSTITUTION` and `POSSESSION_SET` action types. Use the match participant ID, including existing match-wide UNKNOWN where appropriate, or a slot ID belonging to this point. A one-player action uses null `toActorId`. A two-player action requires distinct actor IDs. Elapsed milliseconds are relative to match start. Null means unknown and 0 means match start. Team result registration timestamps supply no gameplay times.

A 200 save increments only the draft revision. A well-typed candidate can be saved with diagnostics. Diagnostic entries contain a `code`, `detail`, and an `eventId` when the problem belongs to an action. The editor can show `MISSING_PULL`, `MISSING_PERSONAL_RESULT`, `EVENT_ORDER`, `CLOCK_OUT_OF_BOUNDS`, `CLOCK_OUT_OF_ORDER`, `ACTOR_NOT_ON_FIELD`, `ACTOR_TEAM_CONFLICT`, `POSSESSION_CONFLICT`, or `SCORING_TEAM_CONFLICT`. The candidate has no effect on the active journal, score, statistics, OBS, or the match interchange export. Database backup and restore preserve the draft.

A later GET can report `DRAFT_BASE_STALE` when the journal or lineup changed since the save. The response retains all saved insertions. An unavailable anchor or actor, or an insertion UUID subsequently used by the journal, is diagnosed and omitted from the preview. To save again, review the candidate and use the current revisions. A retired point or replaced closing marker cannot receive the saved draft, and the stored draft stays bound to its original closing marker.

Malformed shapes, invalid references and unsupported types return 400 `INVALID_REQUEST`. A missing match returns 404. A 409 can report `POINT_RESULT_CLIENT_REQUIRED`, `POINT_RESULT_WRITE_DISABLED`, `MATCH_NOT_FINISHED`, `POINT_COMPLETION_DRAFT_CONFLICT` (point or closing marker), `EVENT_LOG_STALE` (journal), or `POINT_COMPLETION_DRAFT_STALE` (lineup or draft).

## Approve a saved draft

Send `PUT /api/v1/matches/{matchId}/point-lineups/{pointId}/completion-draft/approval`
with capability 4, SPORTS_WRITE and CSRF. The command uses the default-off
`ultistats.point-results.write-enabled` setting, advertised by
`pointCompletionApprovalWriteEnabled` in the point-lineups response.

Send these fields from the saved draft response and a generated operation UUID.

| Field | Value |
| --- | --- |
| `operationId` | UUID scoped to this match and approval command. |
| `expectedClosingEventId` | Canonical active POINT_RESULT UUID. |
| `expectedLogRevision` | Observed journal revision. |
| `expectedLineupRevision` | Observed lineup revision or explicit null. |
| `expectedDraftRevision` | Positive saved draft revision. |

A 200 response returns `operationId`, `pointId`, `closingEventId`,
`draftRevision`, `logRevision`, `lastSequenceNumber`, and `replayed`.
The approval advances the journal revision once. Original events, the team
score and the append cursor retain their values. Fetch the complete history
from v3 after success. An exact authorized retry returns the original
receipt with `replayed: true`, including when the writer is disabled.
A changed payload with an accepted operation UUID returns 409
`EVENT_IDEMPOTENCY_CONFLICT`.

A stale log or draft returns `EVENT_LOG_STALE` or
`POINT_COMPLETION_DRAFT_STALE`. An invalid or incomplete candidate returns
409 `POINT_COMPLETION_APPROVAL_CONFLICT` and `diagnostics` in the same shape
as draft preview. A mismatched scoring side has diagnostic
`SCORING_TEAM_CONFLICT`. The operator can revise the saved draft and retry
approval with current revisions. Restored action IDs must be unique within
the match and distinct from its active and retired append IDs.

Changes to an approved source segment, its opening identity or its lineup
that would rewrite the certified history return 409
`POINT_COMPLETION_APPROVAL_LOCKED`. Generic edits and undo keep their
existing behavior on unrelated points. Approval sends a neutral match
invalidation without a live gameplay event.
