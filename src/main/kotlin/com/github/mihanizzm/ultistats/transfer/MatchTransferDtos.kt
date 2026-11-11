package com.github.mihanizzm.ultistats.transfer

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class MatchExportRequest(val matchIds: List<UUID>)

@Schema(description = "Portable selected-match package. Versions 1 and 2 create independent copies. Version 2 includes standalone announcement intervals.")
data class MatchTransferPackage(
    @field:Schema(description = "Required format version: 1 without standalone announcements, 2 with standalone announcements.", minimum = "1", maximum = "2")
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
    @field:Schema(description = "Active event log in strictly increasing sequenceNumber order; gaps are allowed.")
    val events: List<PortableEvent>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @field:Schema(
        description = "Standalone announcement intervals, omitted when empty. Nonempty intervals require format version 2.",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val announcements: List<PortableAnnouncement> = emptyList(),
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
    val occurredAt: Instant,
    @field:Schema(description = "ONE_PLAYER: acting participant. TWO_PLAYER: throwing participant. Absent for TEAM/SYSTEM.")
    val fromParticipantId: UUID? = null,
    @field:Schema(description = "TWO_PLAYER: receiver or defender; absent for other categories.")
    val toParticipantId: UUID? = null,
    @field:Schema(description = "Only TEAM events use teamId, belonging to this match.")
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
