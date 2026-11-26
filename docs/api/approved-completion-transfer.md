# Approved completion transfer

Use `POST /api/v1/matches/export` with `X-UltiStats-Point-Lineups: 4` and a
body containing `matchIds` and the integer `acceptFormatVersion: 8`. Both transfer
routes require the existing TRANSFER permission and CSRF protection.

A package with any approved point completion uses format 8. Each affected
match includes `approvedCompletions` in canonical point order. Each item
contains `pointId`, `closingEventId`, and the approved `insertions` array in its
stored order. A null anchor inserts at the point start. Other anchors identify
an active original event before its closing marker. Null elapsed time is
unknown and zero is a known offset at match start. The original append rows
carry their existing IDs, sequence numbers, and precise timestamps. The
original marker still owns the team score and opens the next point.

Without an approval, the usual version 1–7 selection and fields apply even when
the client accepts 8. Drafts stay out of portable files. An older reader
requesting an approved match receives 409 `POINT_COMPLETION_TRANSFER_CLIENT_REQUIRED`
with capability 4 or 409 `POINT_RESULT_CLIENT_REQUIRED` without it. Corrupt
approved history fails with 409 `POINT_COMPLETION_HISTORY_CONFLICT` once negotiated.

Public `POST /api/v1/matches/import` rejects valid v8 packages with 409
`POINT_COMPLETION_TRANSFER_IMPORT_DISABLED`. Versions 1–7 keep their existing
import gates. Internal recovery for isolated verification validates all
matches against the existing approved-history integrity rules before
inserting and creates independent copies in one transaction. Public
admission requires the approval command's gameplay and timing checks and
protection of the match data referenced by approved actions. It creates new match, team, participant, event, slot, point, and
restored action identities. The new point is derived from the remapped opening
boundary, and restored anchors and actors refer to remapped original events
and raw slots or participants. Restored IDs belong to the match's approved
history. They must be distinct from that match's append event IDs, including
retired rows. Registration times, elapsed times and ordering are preserved.

For operational backup and recovery, use the PostgreSQL logical snapshot and
start the same or a newer compatible JAR with point-result and approval writes
off. The logical snapshot preserves both approved and draft rows with their
original identities. Earlier binaries must stay disconnected from databases
containing approved records. V8 export, approved history and approved
statistics remain readable with writes off.
