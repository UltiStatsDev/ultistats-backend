package com.github.mihanizzm.ultistats.service

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionHistoryResponse
import com.github.mihanizzm.ultistats.dto.response.ProjectedEvent
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.repository.jpa.PointCompletionApprovalRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
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
        val restoredByIndex = restoredBefore(match, journal)
        val entries = buildList<ProjectedEvent> {
            for ((index, event) in journal.withIndex()) {
                for (restored in restoredByIndex[index].orEmpty()) {
                    add(ProjectedEvent.Restored(size + 1, restored.pointId, restored.markerId, restored.insertion))
                }
                add(ProjectedEvent.Original(size + 1, EventResponse.from(event)))
            }
        }
        val lastSequence = events.findFirstByMatchIdOrderBySequenceNumberDesc(matchId)?.sequenceNumber ?: 0
        return PointCompletionHistoryResponse(entries, lastSequence, match.eventLogRevision)
    }

    private fun restoredBefore(match: Match, journal: List<StoredEvent>): Map<Int, List<RestoredAction>> {
        val snapshots = approvals.findAll(match.id)
        if (snapshots.isEmpty()) return emptyMap()
        if (match.status != MatchStatus.FINISHED) conflict("Approved completion requires a finished match")

        val points = PointTimeline.from(match.id, match.status, journal).associateBy { it.pointId }
        val indices = journal.withIndex().associate { it.value.id to it.index }
        val rosterIds = participants.findAllByMatchId(match.id).mapTo(mutableSetOf()) { it.participantId }
        val slots = lineups.slots(match.id)
        val insertionIds = mutableSetOf<UUID>()
        val byIndex = mutableMapOf<Int, MutableList<RestoredAction>>()

        for (snapshot in snapshots) {
            val point = points[snapshot.pointId] ?: conflict("An approved point is no longer canonical")
            val closing = indices[snapshot.closingEventId]
            if (point.closingEventId != snapshot.closingEventId || closing == null ||
                journal[closing].event.type != EventType.POINT_RESULT) {
                conflict("An approved point no longer has its original active POINT_RESULT marker")
            }
            val start = (indices[point.openingEventId] ?: -1) + 1
            val insertions = decode(snapshot.insertionPayload)
            fun knownActor(id: UUID) = id in rosterIds || slots[id]?.pointId == point.pointId

            for (insertion in insertions) {
                if (!insertionIds.add(insertion.id)) conflict("An approved restored UUID occurs more than once")
                val anchor = insertion.afterEventId?.let { id ->
                    indices[id]?.takeIf { it in start until closing }
                        ?: conflict("An approved insertion anchor is outside its point or no longer active")
                }
                val twoActors = insertion.type.eventType().category == EventCategory.TWO_PLAYER
                val recipient = insertion.toActorId
                if (!knownActor(insertion.fromActorId) ||
                    (recipient != null && (!knownActor(recipient) || recipient == insertion.fromActorId)) ||
                    twoActors != (recipient != null)) {
                    conflict("An approved insertion has invalid actor references")
                }
                if ((insertion.elapsedOffsetMs ?: 0) < 0) conflict("An approved insertion has a negative elapsed offset")
                val before = anchor?.plus(1) ?: start
                byIndex.getOrPut(before) { mutableListOf() }.add(RestoredAction(point.pointId, snapshot.closingEventId, insertion))
            }
        }
        if (insertionIds.chunked(1000).any { events.findAllById(it).isNotEmpty() }) {
            conflict("An approved restored UUID is already used in the append journal")
        }
        return byIndex
    }

    private fun decode(payload: String): List<PointCompletionInsertion> {
        val insertions: List<PointCompletionInsertion?>? = try {
            json.readValue(payload)
        } catch (_: JsonProcessingException) {
            conflict("An approved completion has an unreadable payload")
        }
        if (insertions.isNullOrEmpty() || insertions.size > 500) {
            conflict("An approved completion must have between 1 and 500 insertions")
        }
        return insertions.map { it ?: conflict("An approved completion contains an empty insertion") }
    }

    private fun conflict(detail: String): Nothing = throw PointCompletionHistoryRejected(MatchProblem(
        MatchProblemCode.POINT_COMPLETION_HISTORY_CONFLICT, "Approved completion history conflict", detail,
    ))

    private data class RestoredAction(val pointId: UUID, val markerId: UUID, val insertion: PointCompletionInsertion)
}

class PointCompletionHistoryRejected(val problem: MatchProblem) : RuntimeException(problem.detail)
