# Point substitutions

Enable new substitutions with `ULTISTATS_POINT_SUBSTITUTIONS_WRITE_ENABLED=true` and point-lineup writes with `ULTISTATS_POINT_LINEUPS_WRITE_ENABLED=true`. Both default to false. Public GET `/api/v1/matches/{matchId}/point-lineups` can be used for capability discovery before fetching the journal. The presence of the boolean `substitutionsWriteEnabled` indicates that the server supports the protocol. Its value reports whether the flags allow fresh substitutions.

Send `X-UltiStats-Point-Lineups: 2` on journal requests and point-lineup mutations when the client can replay substitutions. Header value 1 retains existing temporary-slot support. A journal with an active substitution returns 409 `POINT_LINEUP_CLIENT_REQUIRED` to older clients on journal reads and writes. Undo receipt lookup and public SSE retain their existing payloads.

Record `PLAYER_SUBSTITUTION` only through `POST /api/v2/matches/{matchId}/events/batch`. Use the current `expectedLastSequenceNumber` and `expectedLogRevision`. The event has a stable `eventId`, `occurredAt`, and the following fields.

- `fromParticipantId` is the on-field roster participant or raw temporary slot UUID.
- `toParticipantId` is an off-field roster participant on the same team.

The active confirmed point is determined by the guarded journal position after PULL. Either team can substitute, including during an existing timeout or announcement. The event preserves the phase and transfers the disc only if the outgoing actor holds it. BRICK still attributes the original PULL. Both actor IDs are preserved in the journal.

`lineup.teams` remains the original selection, with any later temporary slot identifications. `effectiveTeams` has the same team shape and shows the lineup after applying this point's substitutions. It is null for an unconfirmed point. To prefill the next point from a completed point, use its effective teams and allocate fresh UUIDs for temporary slots. Identification of a departed slot remains available after completion. A roster player who also entered that point cannot be assigned to its historical slot.

Use ordered Undo to restore a substituted lineup and its possession state. PATCH cannot edit a substitution. DELETE is accepted only when the surviving journal remains valid. Disabling fresh writes preserves capability-2 reads, exact replay of accepted batches, and safe Undo. Keep a substitution-aware backend and client after the first substitution write, including after undo because the database keeps the removed event for identity and sequence history. A binary predating substitution support cannot decode those rows. Until a portable format supports the event, transfer rejects export of affected matches and import packages containing substitutions with `POINT_SUBSTITUTION_TRANSFER_UNSUPPORTED`.
