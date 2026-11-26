package com.github.mihanizzm.ultistats.transfer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.github.mihanizzm.ultistats.dto.request.ApprovedPointCompletion
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

internal const val POINT_RESULT_TRANSFER_VERSION = 7
internal const val APPROVED_COMPLETION_TRANSFER_VERSION = 8

data class MatchExportRequest(
    val matchIds: List<UUID>,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Schema(description = "Send 8 with point-lineup capability 4 to accept approved point completions.", allowableValues = ["8"])
    val acceptFormatVersion: Int? = null,
)

@Schema(description = "Portable selected-match package for creating independent copies. Version 8 carries approved point completions. Version 7 carries an active POINT_RESULT. A package can include matches that use earlier features.")
data class MatchTransferPackage(
    @field:Schema(description = "Required format version: 8 with any approved completion, otherwise 7 with any active POINT_RESULT, otherwise 6 with any active introduced reserve in the selected package, otherwise 5 with any active PLAYER_SUBSTITUTION, otherwise 4 with canonical confirmed point lineups, otherwise 3 with POSSESSION_SET, otherwise 2 with standalone announcements, otherwise 1.", minimum = "1", maximum = "8")
    val formatVersion: Int,
    val matches: List<PortableMatch>,
    val teams: List<PortableTeam>,
    val players: List<PortablePlayer>,
)

data class PortableTeam(val id: UUID, val name: String, val city: String? = null)
data class PortablePlayer(val id: UUID, val firstName: String, val lastName: String)

data class PortableMatch(
    val id: UUID,
    val playersPerTeam: Int,
    val plannedStartTimestamp: Instant? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    @field:Schema(description = "Exactly two teams in persisted match order, with historical names and participants.")
    val teams: List<PortableMatchTeam>,
    @field:Schema(description = "Active event log in strictly increasing sequenceNumber order. Gaps are allowed. PLAYER_SUBSTITUTION requires format version 5 through 8. POINT_RESULT requires format version 7 or 8.")
    val events: List<PortableEvent>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @field:Schema(
        description = "Standalone announcement intervals, omitted when empty. Nonempty intervals require format version 2 through 8.",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val announcements: List<PortableAnnouncement> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @field:Schema(
        description = "Canonical confirmed lineups in point order, omitted when empty. Nonempty lineups require format version 4 through 8.",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val pointLineups: List<PortablePointLineup> = emptyList(),
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @param:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    @field:Schema(
        description = "Approved snapshots in canonical point order. Present and nonempty only in version 8.",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val approvedCompletions: List<ApprovedPointCompletion>? = null,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PortableAnnouncement(
    @field:Schema(description = "Identity scoped to the source match, preserved within the new match.")
    val id: UUID,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val teamId: UUID? = null,
    val startedAt: Instant,
    @field:Schema(types = ["string", "null"], format = "date-time")
    val endedAt: Instant? = null,
)

data class PortableMatchTeam(
    val teamId: UUID,
    val teamName: String,
    val participants: List<PortableParticipant>,
)

data class PortableParticipant(
    @field:Schema(description = "PLAYER: source player ID. UNKNOWN: match-scoped identity, unrelated to catalog players.")
    val participantId: UUID,
    val kind: MatchParticipantKind,
    val unknownSlot: Int? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val number: Int? = null,
)

data class PortableEvent(
    val id: UUID,
    val sequenceNumber: Int,
    val type: EventType,
    @field:Schema(description = "Exact event timestamp. For POINT_RESULT it records when the result was registered. The actual time of play is unknown.")
    val occurredAt: Instant,
    @field:Schema(description = "Acting or throwing participant, or new holder for POSSESSION_SET. In versions 4 through 8 an actor may be a point lineup slot. For PLAYER_SUBSTITUTION this is the outgoing on-field player or raw temporary slot.")
    val fromParticipantId: UUID? = null,
    @field:Schema(description = "Receiver or defender for two-player events, including a point lineup slot in versions 4 through 8. For PLAYER_SUBSTITUTION this is an off-field roster player on the same team or, in versions 6 through 8, the new temporary slot introduced by this event.")
    val toParticipantId: UUID? = null,
    @field:Schema(description = "Required for TEAM events, including POINT_RESULT, and optional for ANNOUNCEMENT_START. POINT_RESULT has no participant references. The team must belong to this match. PLAYER_SUBSTITUTION has no teamId.")
    val teamId: UUID? = null,
)

data class MatchTransferResult(
    val matchIds: Map<UUID, UUID>,
    val teamIds: Map<UUID, UUID>,
    val playerIds: Map<UUID, UUID>,
    val eventIds: Map<UUID, UUID>,
    @field:Schema(description = "Source match ID -> source participant ID -> new participant ID, including UNKNOWN.")
    val participantIds: Map<UUID, Map<UUID, UUID>>,
)

class InvalidMatchTransferException(val path: String, detail: String) : RuntimeException("$path: $detail")
class ExportMatchNotFoundException(val matchId: UUID) : RuntimeException("Match $matchId not found")
