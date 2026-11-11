package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupPlayer
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupRequest
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupTemporary
import com.github.mihanizzm.ultistats.dto.request.IdentifyPointLineupSlotRequest
import com.github.mihanizzm.ultistats.dto.response.PointLineupResponse
import com.github.mihanizzm.ultistats.dto.response.PointLineupsResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantId
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PointLineupService(
    private val matches: SpringDataMatchRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val events: SpringDataEventRepository,
    private val lineups: PointLineupRepository,
    @Value("\${ultistats.point-lineups.write-enabled:false}") private val writesEnabled: Boolean,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(matchId: UUID): PointLineupsResponse? {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: return null
        val saved = lineups.list(matchId, teamIds(matchId))
        return PointLineupsResponse(match.eventLogRevision, writesEnabled, timeline(match).map { it.toResponse(saved[it.pointId]) })
    }

    @Transactional
    fun confirm(matchId: UUID, pointId: UUID, request: ConfirmPointLineupRequest): PointLineupResponse? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        val revision = request.expectedLogRevision
        if (revision == null || revision < 0) invalid("expectedLogRevision must be a nonnegative integer")
        val point = timeline(match).firstOrNull { it.pointId == pointId }
            ?: reject(PointLineupProblemCode.POINT_LINEUP_CONFLICT, "Point is no longer canonical for this match")

        val teamIds = teamIds(matchId)
        val requestedTeams = request.teams.associateBy { it.teamId }
        if (request.teams.size != teamIds.size || requestedTeams.keys != teamIds.toSet()) {
            invalid("Include both match teams exactly once")
        }
        val selection = teamIds.map { teamId ->
            PointLineupTeam(teamId, requestedTeams.getValue(teamId).entries.map { entry ->
                when (entry) {
                    is ConfirmPointLineupPlayer -> PointLineupPlayer(entry.participantId)
                    is ConfirmPointLineupTemporary -> PointLineupTemporary(entry.slotId, entry.number)
                }
            })
        }

        val saved = lineups.list(matchId, teamIds)
        saved[pointId]?.let { existing ->
            // Resolution is an attribution change. The original selection remains the retry key.
            val original = existing.teams.map { team -> team.copy(entries = team.entries.map {
                if (it is PointLineupTemporary) it.copy(resolvedParticipantId = null) else it
            }) }
            if (original != selection) {
                reject(PointLineupProblemCode.POINT_LINEUP_CONFLICT, "A different lineup is already confirmed for this point")
            }
            return point.toResponse(existing)
        }

        if (revision != match.eventLogRevision) reject(PointLineupProblemCode.EVENT_LOG_STALE, "The event log revision has changed")
        if (point.status != PointStatus.NEXT) {
            reject(PointLineupProblemCode.POINT_LINEUP_CONFLICT, "Only the next point can receive a new lineup")
        }
        if (!writesEnabled) reject(PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled")

        validateSelection(match, selection, saved.values)?.let { invalid(it) }
        val confirmed = PointLineup(revision = 1, teams = selection)
        lineups.insert(matchId, point, confirmed)
        return point.toResponse(confirmed)
    }

    @Transactional
    fun identify(matchId: UUID, pointId: UUID, slotId: UUID, request: IdentifyPointLineupSlotRequest): PointLineupResponse? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        if (request.expectedLineupRevision < 0) invalid("expectedLineupRevision must be a nonnegative integer")
        if (!writesEnabled) reject(PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled")
        val point = timeline(match).firstOrNull { it.pointId == pointId }
            ?: conflict("Point is no longer canonical for this match")
        if (point.status != PointStatus.COMPLETED || point.closingEventId != request.expectedClosingEventId) {
            conflict("The point must be completed with the expected closing event")
        }

        val saved = lineups.list(matchId, teamIds(matchId))[pointId] ?: conflict("Point has no confirmed lineup")
        if (saved.revision != request.expectedLineupRevision) conflict("The point lineup revision has changed")
        val (team, slot) = saved.teams.firstNotNullOfOrNull { team ->
            team.entries.filterIsInstance<PointLineupTemporary>().firstOrNull { it.slotId == slotId }?.let { team to it }
        } ?: conflict("Temporary slot is not part of this point lineup")

        val candidateId = request.participantId
        if (candidateId == slot.resolvedParticipantId) return point.toResponse(saved)
        if (candidateId != null) {
            val candidate = participants.findById(MatchParticipantId(matchId, candidateId)).orElse(null)
            if (candidate?.kind != MatchParticipantKind.PLAYER || candidate.teamId != team.teamId) {
                conflict("Player must belong to the slot's match team")
            }
            val occupied = team.entries.any {
                when (it) {
                    is PointLineupPlayer -> it.participantId == candidateId
                    is PointLineupTemporary -> it.resolvedParticipantId == candidateId
                }
            }
            if (occupied) conflict("Player already occupies a place in this point lineup")
        }

        lineups.identifySlot(matchId, pointId, slotId, candidateId)
        val updated = saved.copy(revision = saved.revision + 1, teams = saved.teams.map {
            if (it == team) team.copy(entries = team.entries.map { entry ->
                if (entry == slot) slot.copy(resolvedParticipantId = candidateId) else entry
            }) else it
        })
        return point.toResponse(updated)
    }

    private fun validateSelection(match: Match, selection: List<PointLineupTeam>, saved: Collection<PointLineup>): String? {
        val roster = participants.findAllByMatchId(match.id).associateBy { it.participantId }
        val usedPlayers = mutableSetOf<UUID>()
        // Include IDs from confirmed points whose opening boundaries were removed.
        val usedSlots = saved.flatMap { lineup ->
            lineup.teams.flatMap { it.entries.filterIsInstance<PointLineupTemporary>().map { slot -> slot.slotId } }
        }.toMutableSet()
        for (team in selection) {
            if (team.entries.size > match.playersPerTeam) return "Too many entries for team ${team.teamId}"
            val numbers = mutableSetOf<Int>()
            for (entry in team.entries) when (entry) {
                is PointLineupPlayer -> {
                    val player = roster[entry.participantId]
                    if (player?.kind != MatchParticipantKind.PLAYER || player.teamId != team.teamId) {
                        return "Player must belong to the selected match team"
                    }
                    if (!usedPlayers.add(entry.participantId)) return "Duplicate player in lineup"
                }
                is PointLineupTemporary -> {
                    if (entry.slotId in roster || !usedSlots.add(entry.slotId)) return "Temporary slot ID is already used in this match"
                    if (entry.number !in 1..match.playersPerTeam || !numbers.add(entry.number)) {
                        return "Temporary slot numbers must be unique per team and within lineup capacity"
                    }
                }
            }
        }
        return null
    }

    private fun teamIds(matchId: UUID) = matchTeams.findAllByMatchIdOrderByPosition(matchId).map { it.teamId }

    private fun timeline(match: Match) = PointTimeline.from(
        match.id, match.status, events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(match.id).map {
            StoredEvent(it.id, it.sequenceNumber, it.toDomain())
        },
    )

    private fun PointBoundary.toResponse(lineup: PointLineup?) =
        PointLineupResponse(pointId, number, status, pullEventId, closingEventId, lineup)

    private fun conflict(detail: String): Nothing = reject(PointLineupProblemCode.POINT_LINEUP_CONFLICT, detail)

    private fun invalid(detail: String): Nothing = reject(PointLineupProblemCode.INVALID_REQUEST, detail)

    private fun reject(code: PointLineupProblemCode, detail: String): Nothing =
        throw PointLineupRejectedException(code, detail)
}

enum class PointLineupProblemCode {
    INVALID_REQUEST, EVENT_LOG_STALE, POINT_LINEUP_CONFLICT, POINT_LINEUP_WRITE_DISABLED, POINT_LINEUP_CLIENT_REQUIRED,
}

class PointLineupRejectedException(
    val code: PointLineupProblemCode,
    detail: String,
    val batch: PointLineupBatchPosition? = null,
) : RuntimeException(detail) {
    fun forBatch(lastSequenceNumber: Int, logRevision: Long, eventId: UUID? = null, eventIndex: Int? = null) =
        PointLineupRejectedException(code, message.orEmpty(), PointLineupBatchPosition(lastSequenceNumber, logRevision, eventId, eventIndex))
}

data class PointLineupBatchPosition(
    val lastSequenceNumber: Int,
    val logRevision: Long,
    val eventId: UUID?,
    val eventIndex: Int?,
)
