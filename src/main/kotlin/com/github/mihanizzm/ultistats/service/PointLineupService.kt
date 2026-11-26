package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupPlayer
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupRequest
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupTemporary
import com.github.mihanizzm.ultistats.dto.request.IdentifyPointLineupSlotRequest
import com.github.mihanizzm.ultistats.dto.request.PointStarterCorrectionRequest
import com.github.mihanizzm.ultistats.dto.response.PointLineupResponse
import com.github.mihanizzm.ultistats.dto.response.PointLineupsResponse
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionPreview
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.MatchParticipantId
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.events.afterSubstitution
import com.github.mihanizzm.ultistats.model.events.substitutions
import com.github.mihanizzm.ultistats.realtime.MatchRealtimePublisher
import com.github.mihanizzm.ultistats.repository.jpa.IntroducedPointSlot
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import com.github.mihanizzm.ultistats.validation.event.EventSequenceDecision
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import java.util.UUID
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class PointLineupService(
    private val matches: SpringDataMatchRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val events: SpringDataEventRepository,
    private val lineups: PointLineupRepository,
    private val policy: PointLineupEventPolicy,
    private val clock: Clock,
    private val sequencePolicy: EventSequencePolicy,
    private val realtimePublisher: MatchRealtimePublisher,
    @Value("\${ultistats.point-lineups.write-enabled:false}") private val writesEnabled: Boolean,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(matchId: UUID): PointLineupsResponse? {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: return null
        val saved = lineups.list(matchId, teamIds(matchId))
        val journal = journal(matchId)
        val points = PointTimeline.from(match.id, match.status, journal)
        val byPoint = PointTimeline.eventsByPoint(points, journal)
        val introduced = lineups.introducedSlots(matchId).groupBy { it.pointId }
        return PointLineupsResponse(
            match.eventLogRevision, writesEnabled, policy.substitutionsWriteEnabled, policy.incomingReservesWriteEnabled,
            nextLineupEditsWriteEnabled = writesEnabled,
            starterCorrectionsWriteEnabled = writesEnabled,
            points = points.map { point ->
                val pointJournal = byPoint[point.pointId].orEmpty()
                val slots = activeSlots(introduced[point.pointId].orEmpty().map { it.slot }, pointJournal)
                point.toResponse(saved[point.pointId], pointJournal.substitutions(), slots)
            },
        )
    }

    @Transactional
    fun confirm(
        matchId: UUID, pointId: UUID, request: ConfirmPointLineupRequest, client: PointLineupClient = PointLineupClient.LEGACY,
    ): PointLineupResponse? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        policy.requireSubstitutionReadClient(matchId, client)
        val revision = request.expectedLogRevision
        if (revision == null || revision < 0) invalid("expectedLogRevision must be a nonnegative integer")
        val expectedLineupRevision = request.expectedLineupRevision
        if (expectedLineupRevision != null && expectedLineupRevision < 0) {
            invalid("expectedLineupRevision must be a nonnegative integer")
        }
        val journal = journal(matchId)
        val points = PointTimeline.from(match.id, match.status, journal)
        val point = points.firstOrNull { it.pointId == pointId }
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
        val introduced = lineups.introducedSlots(matchId)
        val existing = saved[pointId]
        if (expectedLineupRevision == null && existing != null) {
            if (existing.selection() != selection) {
                conflict("A different lineup is already confirmed for this point")
            }
            val pointJournal = PointTimeline.eventsByPoint(points, journal)[pointId].orEmpty()
            return point.toResponse(existing, pointJournal.substitutions(),
                activeSlots(introduced.filter { it.pointId == pointId }.map { it.slot }, pointJournal))
        }

        if (revision != match.eventLogRevision) reject(PointLineupProblemCode.EVENT_LOG_STALE, "The event log revision has changed")
        if (point.status != PointStatus.NEXT) {
            val detail = if (expectedLineupRevision == null) "Only the next point can receive a new lineup"
                else "Only a confirmed next point can be edited before PULL"
            conflict(detail)
        }
        if (!writesEnabled) reject(PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled")
        if (expectedLineupRevision != null) {
            return editNext(match, point, selection, saved, introduced, expectedLineupRevision)
        }

        validateSelection(match, selection, saved.values, introduced.mapTo(mutableSetOf()) { it.slot.slotId })?.let { invalid(it) }
        val confirmed = PointLineup(revision = 1, teams = selection)
        lineups.insert(matchId, point, confirmed, clock.instant())
        return point.toResponse(confirmed)
    }

    @Transactional
    fun previewStarterCorrection(
        matchId: UUID, pointId: UUID, request: PointStarterCorrectionRequest, client: PointLineupClient,
    ): PointStarterCorrectionPreview? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        return prepareStarterCorrection(match, pointId, request, client).correction.preview
    }

    @Transactional
    fun correctStarter(
        matchId: UUID, pointId: UUID, request: PointStarterCorrectionRequest, client: PointLineupClient,
    ): PointStarterCorrectionResponse? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        val (point, correction, pointJournal, slots) = prepareStarterCorrection(match, pointId, request, client)
        lineups.replaceStarter(matchId, pointId, request.fromParticipantId, request.toParticipantId)
        events.saveAll(correction.changedRows.values)
        match.eventLogRevision++
        match.lastEventLogInvalidationRevision = match.eventLogRevision

        val changedEvent = correction.preview.changedEvents.firstOrNull()
        if (changedEvent == null) {
            realtimePublisher.matchChanged(matchId)
        } else {
            realtimePublisher.eventLogChanged(matchId, MatchRealtimeOperation.UPDATED, changedEvent.eventId)
        }
        return PointStarterCorrectionResponse(
            match.eventLogRevision, point.toResponse(correction.lineup, pointJournal.substitutions(), slots), correction.preview,
        )
    }

    private data class PreparedStarterCorrection(
        val point: PointBoundary,
        val correction: PointStarterCorrection,
        val pointJournal: List<StoredEvent>,
        val slots: List<PointSubstitutionSlot>,
    )

    /** Both callers hold the same match-row lock as event writes. */
    private fun prepareStarterCorrection(
        match: Match, pointId: UUID, request: PointStarterCorrectionRequest, client: PointLineupClient,
    ): PreparedStarterCorrection {
        policy.requireSubstitutionReadClient(match.id, client)
        if (!writesEnabled) reject(PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled")
        if (request.expectedLogRevision < 0 || request.expectedLineupRevision < 0) invalid("Expected revisions must be nonnegative integers")
        if (request.fromParticipantId == request.toParticipantId) invalid("The two starter participants must be different")
        if (match.eventLogRevision != request.expectedLogRevision) {
            reject(PointLineupProblemCode.EVENT_LOG_STALE, "The event log revision has changed")
        }
        val rows = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(match.id)
        val journal = rows.map { it.toStored() }
        val point = PointTimeline.from(match.id, match.status, journal).firstOrNull { it.pointId == pointId }
            ?: conflict("Point is no longer canonical for this match")
        if (point.status != PointStatus.COMPLETED || point.closingEventId != request.expectedClosingEventId) {
            conflict("The point must be completed with the expected closing event")
        }
        val saved = lineups.list(match.id, teamIds(match.id))[pointId] ?: conflict("Point has no confirmed lineup")
        if (saved.revision != request.expectedLineupRevision) conflict("The point lineup revision has changed")

        val openingIndex = rows.indexOfFirst { it.id == point.openingEventId }
        val closingIndex = rows.indexOfFirst { it.id == point.closingEventId }
        val pointRows = rows.subList(openingIndex + 1, closingIndex + 1)
        val pointEventIds = pointRows.map { it.id }.toSet()
        val introduced = lineups.introducedSlots(match.id).filter {
            it.pointId == pointId && it.slot.introducedByEventId in pointEventIds
        }
        // Portable lineups apply point-wide uniqueness to identified reserve identities.
        if (introduced.any { it.slot.resolvedParticipantId == request.toParticipantId }) {
            conflict("The replacement player is already identified as an incoming reserve in this point")
        }
        val roster = participants.findAllByMatchId(match.id)
        val rosterTeams = roster.filter { it.kind == MatchParticipantKind.PLAYER }.associate { it.participantId to it.teamId }
        val unknownIds = roster.filter { it.kind == MatchParticipantKind.UNKNOWN }.map { it.participantId }.toSet()
        val correction = PointStarterCorrection.propose(point, pointRows, saved, request, rosterTeams)
        val proposedJournal = journal.map { correction.changedRows[it.id]?.toStored() ?: it }
        val proposedPoint = proposedJournal.subList(openingIndex + 1, closingIndex + 1)
        val confirmed = ConfirmedJournal(mapOf(pointId to correction.lineup), unknownIds, rosterTeams, writesEnabled, introduced)
        try {
            confirmed.validateSubstitutionPoint(pointId, proposedPoint)
        } catch (error: PointLineupRejectedException) {
            conflict("The corrected point cannot be replayed: ${error.message}")
        }
        val decision = sequencePolicy.validate(proposedJournal.map { it.event }, requirePointEnded = match.status == MatchStatus.FINISHED)
        if (decision is EventSequenceDecision.Rejected) {
            val violation = decision.violation
            conflict("The corrected journal cannot be replayed at event index ${violation.eventIndex} in state ${violation.currentState}")
        }
        return PreparedStarterCorrection(point, correction, proposedPoint, introduced.map { it.slot })
    }

    private fun EventEntity.toStored() = StoredEvent(id, sequenceNumber, toDomain())

    @Transactional
    fun identify(
        matchId: UUID, pointId: UUID, slotId: UUID, request: IdentifyPointLineupSlotRequest, client: PointLineupClient = PointLineupClient.LEGACY,
    ): PointLineupResponse? {
        val match = matches.findByIdForUpdate(matchId) ?: return null
        policy.requireSubstitutionReadClient(matchId, client)
        if (request.expectedLineupRevision < 0) invalid("expectedLineupRevision must be a nonnegative integer")
        if (!writesEnabled) reject(PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled")
        val journal = journal(matchId)
        val points = PointTimeline.from(match.id, match.status, journal)
        val point = points.firstOrNull { it.pointId == pointId }
            ?: conflict("Point is no longer canonical for this match")
        val pointJournal = PointTimeline.eventsByPoint(points, journal)[pointId].orEmpty()
        val replacements = pointJournal.substitutions()
        val introduced = activeSlots(lineups.introducedSlots(matchId).filter { it.pointId == pointId }.map { it.slot }, pointJournal)
        if (point.status != PointStatus.COMPLETED || point.closingEventId != request.expectedClosingEventId) {
            conflict("The point must be completed with the expected closing event")
        }

        val saved = lineups.list(matchId, teamIds(matchId))[pointId] ?: conflict("Point has no confirmed lineup")
        if (saved.revision != request.expectedLineupRevision) conflict("The point lineup revision has changed")
        val introducedSlot = introduced.firstOrNull { it.slotId == slotId }
        val (team, slot) = saved.teams.firstNotNullOfOrNull { team ->
            val temporary = team.entries.filterIsInstance<PointLineupTemporary>().firstOrNull { it.slotId == slotId }
                ?: introducedSlot?.takeIf { it.teamId == team.teamId }?.asTemporary()
            temporary?.let { team to it }
        } ?: conflict("Temporary slot is not part of this point lineup")

        val candidateId = request.participantId
        if (candidateId == slot.resolvedParticipantId) return point.toResponse(saved, replacements, introduced)
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
            if (occupied || introduced.any { it.resolvedParticipantId == candidateId } ||
                replacements.any { it.toParticipant == candidateId }) {
                conflict("Player already occupies or occupied a place in this point lineup")
            }
        }

        lineups.identifySlot(matchId, pointId, slotId, candidateId)
        val updated = saved.copy(revision = saved.revision + 1, teams = saved.teams.map {
            if (it == team) team.copy(entries = team.entries.map { entry ->
                if (entry == slot) slot.copy(resolvedParticipantId = candidateId) else entry
            }) else it
        })
        return point.toResponse(updated, replacements, introduced.map {
            if (it.slotId == slotId) it.copy(resolvedParticipantId = candidateId) else it
        })
    }

    private fun editNext(
        match: Match,
        point: PointBoundary,
        selection: List<PointLineupTeam>,
        saved: Map<UUID, PointLineup>,
        introduced: List<IntroducedPointSlot>,
        expectedRevision: Long,
    ): PointLineupResponse {
        val existing = saved[point.pointId] ?: conflict("Point has no confirmed lineup")
        val sameSelection = selection == existing.selection()
        // With the same journal base, a retry can only read back an already-applied selection.
        val replay = sameSelection && expectedRevision > 0 && expectedRevision == existing.revision - 1
        if (expectedRevision != existing.revision && !replay) {
            conflict("The point lineup revision has changed")
        }
        if (sameSelection) return point.toResponse(existing)

        val originalSlots = existing.teams.flatMap { team ->
            team.entries.filterIsInstance<PointLineupTemporary>().map { it.slotId to (team.teamId to it) }
        }.toMap()
        val retainedSlots = mutableSetOf<UUID>()
        val updatedTeams = selection.map { team ->
            team.copy(entries = team.entries.map { entry ->
                val original = (entry as? PointLineupTemporary)?.let { originalSlots[it.slotId] }
                if (original == null) {
                    entry
                } else {
                    val (teamId, slot) = original
                    if (team.teamId != teamId || entry != slot.copy(resolvedParticipantId = null)) {
                        conflict("A retained temporary slot must keep its team and number")
                    }
                    retainedSlots.add(slot.slotId)
                    slot
                }
            })
        }
        validateSelection(match, updatedTeams, (saved - point.pointId).values,
            introduced.mapTo(mutableSetOf()) { it.slot.slotId })?.let { invalid(it) }

        val removedSlots = originalSlots - retainedSlots
        if (removedSlots.values.any { (_, slot) -> slot.resolvedParticipantId != null } ||
            lineups.hasEventSlotReference(match.id, removedSlots.keys)) {
            conflict("A temporary slot with an identification or event history cannot be removed")
        }
        val reservedNumbers = introduced.filter { it.pointId == point.pointId }.map { it.slot.teamId to it.slot.number }.toSet()
        if (updatedTeams.any { team ->
                team.entries.filterIsInstance<PointLineupTemporary>().any { (team.teamId to it.number) in reservedNumbers }
            }) {
            conflict("An archived incoming reserve already uses the temporary slot number")
        }

        val updated = PointLineup(existing.revision + 1, updatedTeams)
        lineups.replaceSelection(match.id, point.pointId, updated, retainedSlots, removedSlots.keys)
        return point.toResponse(updated)
    }

    // Resolution is an attribution change. The requested selection contains only raw actors.
    private fun PointLineup.selection() = teams.map { team -> team.copy(entries = team.entries.map {
        if (it is PointLineupTemporary) it.copy(resolvedParticipantId = null) else it
    }) }

    private fun validateSelection(
        match: Match, selection: List<PointLineupTeam>, saved: Collection<PointLineup>, usedSlots: MutableSet<UUID>,
    ): String? {
        val roster = participants.findAllByMatchId(match.id).associateBy { it.participantId }
        val usedPlayers = mutableSetOf<UUID>()
        // Include IDs from confirmed points whose opening boundaries were removed.
        saved.forEach { lineup ->
            lineup.teams.forEach { team ->
                team.entries.filterIsInstance<PointLineupTemporary>().mapTo(usedSlots) { it.slotId }
            }
        }
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
                    if (entry.resolvedParticipantId?.let { !usedPlayers.add(it) } == true) return "Duplicate player in lineup"
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

    private fun journal(matchId: UUID) = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        .map { it.toStored() }

    private fun activeSlots(slots: List<PointSubstitutionSlot>, journal: List<StoredEvent>): List<PointSubstitutionSlot> {
        val activeEvents = journal.map { it.id }.toSet()
        return slots.filter { it.introducedByEventId in activeEvents }
    }

    private fun PointBoundary.toResponse(
        lineup: PointLineup?,
        replacements: List<TwoPlayerEvent> = emptyList(),
        slots: List<PointSubstitutionSlot> = emptyList(),
    ): PointLineupResponse {
        val incoming = slots.associate { it.slotId to it.asTemporary() }
        val effective = lineup?.teams?.let { initial ->
            replacements.fold(initial) { teams, replacement ->
                incoming[replacement.toParticipant]?.let { teams.afterSubstitution(replacement, it) }
                    ?: teams.afterSubstitution(replacement)
            }
        }
        return PointLineupResponse(pointId, number, status, pullEventId, closingEventId, lineup, effective, slots)
    }

    private fun conflict(detail: String): Nothing = reject(PointLineupProblemCode.POINT_LINEUP_CONFLICT, detail)

    private fun invalid(detail: String): Nothing = reject(PointLineupProblemCode.INVALID_REQUEST, detail)

    private fun reject(code: PointLineupProblemCode, detail: String): Nothing =
        throw PointLineupRejectedException(code, detail)
}

enum class PointLineupProblemCode {
    INVALID_REQUEST, EVENT_LOG_STALE, POINT_LINEUP_CONFLICT, POINT_LINEUP_WRITE_DISABLED, POINT_LINEUP_CLIENT_REQUIRED,
    POINT_SUBSTITUTION_WRITE_DISABLED, POINT_SUBSTITUTION_CONFLICT,
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
