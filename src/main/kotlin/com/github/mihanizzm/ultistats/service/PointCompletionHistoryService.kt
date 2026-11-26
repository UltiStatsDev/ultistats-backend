package com.github.mihanizzm.ultistats.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.dto.request.ApprovedPointCompletion
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionHistoryResponse
import com.github.mihanizzm.ultistats.dto.response.ProjectedEvent
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.scoringTeam
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.repository.jpa.PointCompletionApprovalRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupSlotActor
import com.github.mihanizzm.ultistats.repository.jpa.StoredPointApproval
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PointCompletionHistoryService(
    private val matches: SpringDataMatchRepository,
    private val events: SpringDataEventRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val lineups: PointLineupRepository,
    private val pointLineups: PointLineupEventPolicy,
    private val approvals: PointCompletionApprovalRepository,
    private val json: ObjectMapper,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(matchId: UUID, client: PointLineupClient): PointCompletionHistoryResponse? {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: return null
        val active = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        pointLineups.requireReadClient(matchId, active, client)
        val journal = active.map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) }
        val entries = project(match, journal).mapIndexed { index, entry ->
            when (entry) {
                is CompletionHistoryEntry.Original -> ProjectedEvent.Original(index + 1, EventResponse.from(entry.event))
                is CompletionHistoryEntry.Restored -> ProjectedEvent.Restored(
                    index + 1, entry.pointId, entry.markerId, entry.insertion,
                )
            }
        }
        val lastSequence = events.findFirstByMatchIdOrderBySequenceNumberDesc(matchId)?.sequenceNumber ?: 0
        return PointCompletionHistoryResponse(entries, lastSequence, match.eventLogRevision)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    internal fun approvedProjection(match: Match): List<CompletionHistoryEntry>? {
        val stored = approvals.findAll(match.id)
        if (stored.isEmpty()) return null
        val journal = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(match.id)
            .map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) }
        return project(match, journal, stored)
    }

    private fun project(
        match: Match, journal: List<StoredEvent>, stored: List<StoredPointApproval> = approvals.findAll(match.id),
    ): List<CompletionHistoryEntry> {
        val (_, restoredByIndex) = loadApprovals(match, journal, stored)
        return buildList {
            for ((index, event) in journal.withIndex()) {
                addAll(restoredByIndex[index].orEmpty())
                add(CompletionHistoryEntry.Original(event))
            }
        }
    }

    internal fun transferableApprovals(match: Match, journal: List<StoredEvent>): List<ApprovedPointCompletion> =
        loadApprovals(match, journal, approvals.findAll(match.id)).first

    private fun loadApprovals(
        match: Match, journal: List<StoredEvent>, stored: List<StoredPointApproval>,
    ): Pair<List<ApprovedPointCompletion>, Map<Int, List<CompletionHistoryEntry.Restored>>> {
        if (stored.isEmpty()) return emptyList<ApprovedPointCompletion>() to emptyMap()
        val points = PointTimeline.from(match.id, match.status, journal).associateBy { it.pointId }
        val snapshots = stored.map(::decode)
        val rosterTeams = participants.findAllByMatchId(match.id).associate { it.participantId to it.teamId }
        val restored = validateApprovedCompletions(match.status, journal, points, snapshots, rosterTeams, lineups.slots(match.id))
        val insertionIds = snapshots.flatMap { it.insertions }.map { it.id }
        if (insertionIds.chunked(1000).any { events.existsByMatchIdAndIdIn(match.id, it) }) {
            approvalConflict("An approved restored UUID is already used in the append journal")
        }
        return snapshots.sortedBy { points.getValue(it.pointId).number } to restored
    }

    // Callers hold the match lock. The source segment stays certified, including its opening identity.
    internal fun mutationConflict(match: Match, journal: List<StoredEvent>, original: StoredEvent,
                                  replacement: Event?): MatchProblem? {
        if (replacement == original.event) return null
        val approvedIds = approvals.pointIds(match.id)
        if (approvedIds.isEmpty()) return null
        val points = PointTimeline.from(match.id, match.status, journal)
        val byPoint = PointTimeline.eventsByPoint(points, journal)
        val touchesApproval = points.any { point ->
            point.pointId in approvedIds && (byPoint[point.pointId].orEmpty().any { it.id == original.id } ||
                (point.openingEventId == original.id && (replacement == null ||
                    openingScoreChanged(match.id, original.event, replacement))))
        }
        return if (touchesApproval) approvedMutationProblem() else null
    }

    private fun openingScoreChanged(matchId: UUID, before: Event, after: Event): Boolean {
        val roster = participants.findAllByMatchId(matchId).associate { it.participantId to it.teamId }
        val slots = lineups.slots(matchId)
        val teamOf: (UUID) -> UUID? = { roster[it] ?: slots[it]?.teamId }
        return before.scoringTeam(teamOf) != after.scoringTeam(teamOf)
    }

    internal fun restoredIds(matchId: UUID): Set<UUID> =
        approvals.findAll(matchId).flatMap { decode(it).insertions }.mapTo(mutableSetOf()) { it.id }

    private fun decode(stored: StoredPointApproval): ApprovedPointCompletion {
        val insertions: List<PointCompletionInsertion?>? = try {
            json.readValue(stored.insertionPayload)
        } catch (_: JsonProcessingException) {
            approvalConflict("An approved completion has an unreadable payload")
        }
        if (insertions.isNullOrEmpty() || insertions.size > 500) {
            approvalConflict("An approved completion must have between 1 and 500 insertions")
        }
        return ApprovedPointCompletion(stored.pointId, stored.closingEventId, insertions.map {
            it ?: approvalConflict("An approved completion contains an empty insertion")
        })
    }
}

internal fun validateApprovedCompletions(
    status: MatchStatus,
    journal: List<StoredEvent>,
    points: Map<UUID, PointBoundary>,
    snapshots: List<ApprovedPointCompletion>,
    rosterTeams: Map<UUID, UUID>,
    slots: Map<UUID, PointLineupSlotActor>,
): Map<Int, List<CompletionHistoryEntry.Restored>> {
    if (status != MatchStatus.FINISHED) approvalConflict("Approved completion requires a finished match")
    val indices = journal.withIndex().associate { it.value.id to it.index }
    val insertionIds = mutableSetOf<UUID>()
    val approvedPoints = mutableSetOf<UUID>()
    val byIndex = mutableMapOf<Int, MutableList<CompletionHistoryEntry.Restored>>()

    for (snapshot in snapshots) {
        val point = points[snapshot.pointId] ?: approvalConflict("An approved point is no longer canonical")
        if (!approvedPoints.add(point.pointId)) approvalConflict("A point has multiple approved snapshots")
        val closing = indices[snapshot.closingEventId]
        if (point.closingEventId != snapshot.closingEventId || closing == null ||
            journal[closing].event.type != EventType.POINT_RESULT) {
            approvalConflict("An approved point no longer has its original active POINT_RESULT marker")
        }
        val start = (indices[point.openingEventId] ?: -1) + 1
        val scoringTeam = (journal[closing].event as TeamEvent).team
        fun teamOf(id: UUID) = rosterTeams[id] ?: slots[id]?.takeIf { it.pointId == point.pointId }?.teamId
        if (snapshot.insertions.size !in 1..500) {
            approvalConflict("An approved completion must have between 1 and 500 insertions")
        }
        for (insertion in snapshot.insertions) {
            if (!insertionIds.add(insertion.id)) approvalConflict("An approved restored UUID occurs more than once")
            if (insertion.id in indices) approvalConflict("An approved restored UUID is already used in the append journal")
            val anchor = insertion.afterEventId?.let { id ->
                indices[id]?.takeIf { it in start until closing }
                    ?: approvalConflict("An approved insertion anchor is outside its point or no longer active")
            }
            val type = insertion.type.eventType()
            val recipient = insertion.toActorId
            val fromTeam = teamOf(insertion.fromActorId)
            val toTeam = recipient?.let(::teamOf)
            if (fromTeam == null ||
                (recipient != null && (toTeam == null || recipient == insertion.fromActorId)) ||
                (type.category == EventCategory.TWO_PLAYER) != (recipient != null)) {
                approvalConflict("An approved insertion has invalid actor references")
            }
            if ((insertion.elapsedOffsetMs ?: 0) < 0) approvalConflict("An approved insertion has a negative elapsed offset")
            val before = anchor?.plus(1) ?: start
            if (type.closesPoint && (toTeam != scoringTeam || (type == EventType.GOAL) != (fromTeam == toTeam))) {
                approvalConflict("An approved personal result disagrees with its canonical scoring team")
            }
            if (type.closesPoint && before != closing) {
                approvalConflict("An approved personal result must immediately precede its canonical marker")
            }
            byIndex.getOrPut(before) { mutableListOf() }
                .add(CompletionHistoryEntry.Restored(point.pointId, snapshot.closingEventId, insertion))
        }
        if (byIndex[closing].orEmpty().dropLast(1).any { it.insertion.type.eventType().closesPoint }) {
            approvalConflict("An approved personal result must be the final action in its point")
        }
    }
    return byIndex
}

private fun approvalConflict(detail: String): Nothing = throw PointCompletionHistoryRejected(MatchProblem(
    MatchProblemCode.POINT_COMPLETION_HISTORY_CONFLICT, "Approved completion history conflict", detail,
))

internal sealed interface CompletionHistoryEntry {
    data class Original(val event: StoredEvent) : CompletionHistoryEntry
    data class Restored(val pointId: UUID, val markerId: UUID, val insertion: PointCompletionInsertion) : CompletionHistoryEntry
}

class PointCompletionHistoryRejected(val problem: MatchProblem) : RuntimeException(problem.detail)

internal fun approvedMutationProblem() = MatchProblem(
    MatchProblemCode.POINT_COMPLETION_APPROVAL_LOCKED,
    "Approved point protected",
    "This change would alter a point or boundary used by an approved completion",
)
