package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ObsExactEventLogSnapshot(
    val matchId: UUID,
    val teams: List<ObsScoreboardTeam>,
    val logRevision: Long,
    val serverNow: Instant,
    @field:Schema(description = "All active canonical event categories in sequenceNumber order.")
    val events: List<EventResponse>,
    @field:Schema(description = "Confirmed removals in (serverNow - 60s, serverNow], ordered by cancelledAt and sequenceNumber.")
    val retractions: List<ObsEventRetraction>,
    @field:Schema(description = "Persisted participants referenced by the returned events or by their temporary slots.")
    val participants: List<ObsEventLogParticipant>,
    @field:Schema(description = "Referenced temporary actors, including slots with an undone introduction.")
    val temporarySlots: List<ObsEventLogSlot>,
)

data class ObsEventRetraction(
    @field:Schema(description = "Canonical payload at removal. Identity is the snapshot matchId and event.id.")
    val event: EventResponse,
    @field:Schema(description = "Persisted server deletion timestamp.")
    val cancelledAt: Instant,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsEventLogParticipant(
    val participantId: UUID,
    val teamId: UUID,
    val kind: MatchParticipantKind,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val firstName: String?,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val lastName: String?,
    @field:Schema(types = ["integer", "null"], format = "int32", requiredMode = Schema.RequiredMode.REQUIRED)
    val number: Int?,
    @field:Schema(types = ["integer", "null"], format = "int32", requiredMode = Schema.RequiredMode.REQUIRED)
    val unknownSlot: Int?,
)

data class ObsEventLogSlot(
    val slotId: UUID,
    val pointId: UUID,
    val teamId: UUID,
    val number: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val resolvedParticipantId: UUID?,
)

@Service
class ObsExactEventLogService(
    private val keys: ObsSourceKeyService,
    private val matches: SpringDataMatchRepository,
    private val events: SpringDataEventRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val lineups: PointLineupRepository,
    private val scoreboard: ObsScoreboardService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun snapshot(matchId: UUID, authorization: List<String>): ObsExactEventLogSnapshot {
        keys.authorizeSource(matchId, ObsSourceKeyScope.EXACT_EVENT_LOG, authorization)
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val active = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        val removed = events.findRetractions(matchId, now.minusSeconds(60), now)
        val rows = active + removed
        val slotIds = buildSet {
            rows.forEach { row ->
                row.fromSlotId?.let(::add)
                row.toSlotId?.let(::add)
            }
        }
        val slots = lineups.referencedSlots(matchId, slotIds).sortedBy { it.slotId }
        val participantIds = buildSet {
            rows.forEach { row ->
                row.fromParticipantId?.let(::add)
                row.toParticipantId?.let(::add)
            }
            slots.forEach { it.resolvedParticipantId?.let(::add) }
        }
        val actors = participantIds.chunked(1000)
            .flatMap { participants.findAllByMatchIdAndParticipantIdIn(matchId, it) }
            .sortedBy { it.participantId }
            .map {
                ObsEventLogParticipant(it.participantId, it.teamId, it.kind, it.firstName, it.lastName, it.number, it.unknownSlot)
            }
        return ObsExactEventLogSnapshot(
            matchId, scoreboard.teams(matchId), match.eventLogRevision, now,
            active.map { it.response() },
            removed.map { ObsEventRetraction(it.response(), requireNotNull(it.deletedAt)) },
            actors,
            slots.map { ObsEventLogSlot(it.slotId, it.pointId, it.teamId, it.number, it.resolvedParticipantId) },
        )
    }

    private fun EventEntity.response() = EventResponse.from(StoredEvent(id, sequenceNumber, toDomain()))
}
