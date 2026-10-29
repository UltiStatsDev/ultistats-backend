package com.github.mihanizzm.ultistats.transfer

import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class MatchExportRequest(val matchIds: List<UUID>)

@Schema(description = "Portable selected-match package. Version 1 creates independent copies; no photos, current memberships, deleted events or request fingerprints.")
data class MatchTransferPackage(
    @field:Schema(description = "Required format version. Only integer 1 is supported.", allowableValues = ["1"])
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
