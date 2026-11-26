package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.dto.request.PutPointCompletionDraftRequest
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnostic
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnosticCode.*
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraft
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraftResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionPreviewEntry
import com.github.mihanizzm.ultistats.dto.response.TeamEventResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipant
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.scoringTeam
import com.github.mihanizzm.ultistats.repository.jpa.PointCompletionDraftRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import com.github.mihanizzm.ultistats.repository.jpa.StoredCompletionDraft
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PointCompletionDraftService(
    private val matches: SpringDataMatchRepository,
    private val events: SpringDataEventRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val lineups: PointLineupRepository,
    private val drafts: PointCompletionDraftRepository,
    private val sequencePolicy: EventSequencePolicy,
    @Value("\${ultistats.point-results.write-enabled:false}") private val writesEnabled: Boolean,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(matchId: UUID, pointId: UUID, client: PointLineupClient): PointCompletionDraftResponse? {
        checkClient(client)
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: return null
        val point = snapshot(match, pointId)
        return response(point, currentDraft(point))
    }

    @Transactional
    fun save(matchId: UUID, pointId: UUID, request: PutPointCompletionDraftRequest,
             client: PointLineupClient): PointCompletionDraftResponse? {
        checkClient(client)
        val match = matches.findByIdForUpdate(matchId) ?: return null
        if (!writesEnabled) reject(POINT_RESULT_WRITE_DISABLED, "Point result writes are disabled")
        if (request.expectedLogRevision < 0 || request.expectedDraftRevision < 0 ||
            (request.expectedLineupRevision ?: 0) < 0) invalid("Expected revisions must be nonnegative")
        if (request.expectedLogRevision != match.eventLogRevision) reject(EVENT_LOG_STALE, "The event log revision has changed")
        val point = snapshot(match, pointId)
        if (request.expectedClosingEventId != point.marker.id) conflict("The closing event has changed")
        if (request.expectedLineupRevision != point.lineup?.revision) {
            reject(POINT_COMPLETION_DRAFT_STALE, "The point lineup revision has changed")
        }
        val revision = currentDraft(point)?.revision ?: 0
        if (request.expectedDraftRevision != revision) reject(POINT_COMPLETION_DRAFT_STALE, "The draft revision has changed")
        if (request.insertions.size > 500 || request.insertions.map { it.id }.toSet().size != request.insertions.size) {
            invalid("Supply at most 500 insertions with distinct IDs")
        }
        for (insertion in request.insertions) {
            val twoActors = insertion.type.eventType().category == EventCategory.TWO_PLAYER
            if (twoActors != (insertion.toActorId != null) || insertion.fromActorId == insertion.toActorId) {
                invalid("Insertion ${insertion.id} requires distinct actors in the correct number for its type")
            }
        }
        val stored = StoredCompletionDraft(revision + 1, PointCompletionDraft(
            point.marker.id, match.eventLogRevision, point.lineup?.revision, request.insertions,
        ))
        val result = response(point, stored, saving = true)
        drafts.save(matchId, pointId, stored)
        return result
    }

    private fun currentDraft(point: CompletionPoint): StoredCompletionDraft? =
        drafts.find(point.match.id, point.pointId)?.also {
            if (it.draft.closingEventId != point.marker.id) conflict("The stored draft belongs to a different closing event")
        }

    private fun snapshot(match: Match, pointId: UUID): CompletionPoint {
        if (match.status != MatchStatus.FINISHED) reject(MATCH_NOT_FINISHED, "Completion drafts require a finished match")
        val journal = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(match.id)
            .map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) }
        val point = PointTimeline.from(match.id, match.status, journal).firstOrNull { it.pointId == pointId }
            ?: conflict("This point is no longer canonical for this match")
        val opening = journal.indexOfFirst { it.id == point.openingEventId }
        val closing = journal.indexOfFirst { it.id == point.closingEventId }
        val marker = journal.getOrNull(closing)?.takeIf { it.event.type == EventType.POINT_RESULT }
            ?: conflict("Only points closed by an active POINT_RESULT can be completed")
        val source = journal.subList(opening + 1, closing)
        val roster = participants.findAllByMatchId(match.id)
        val slots = lineups.slots(match.id)
        val introduced = lineups.introducedSlots(match.id)
        val sourceIds = source.map { it.id }.toSet()
        val pointSlots = introduced.filter { it.pointId == pointId && it.slot.introducedByEventId in sourceIds }
            .associate { it.slot.slotId to it.slot }
        val rosterTeams = roster.associate { it.participantId to it.teamId }
        val teamIds = matchTeams.findAllByMatchIdOrderByPosition(match.id).map { it.teamId }
        val lineup = lineups.list(match.id, teamIds)[pointId]
        val starters = lineup?.teams.orEmpty().flatMap { team ->
            team.entries.filterIsInstance<PointLineupTemporary>().map { it.slotId to team.teamId }
        }.toMap()
        val previousScore = journal.getOrNull(opening)?.event?.scoringTeam { rosterTeams[it] ?: slots[it]?.teamId }
        val before = journal.subList(0, opening + 1).lastOrNull { it.event.type != EventType.POINT_RESULT }
        val after = journal.subList(closing + 1, journal.size).firstOrNull { it.event.type != EventType.POINT_RESULT }
        return CompletionPoint(
            match, pointId, marker, source, opening < 0, before?.event?.occurredAt, after?.event?.occurredAt, previousScore,
            lineup, roster, pointSlots, rosterTeams + starters + pointSlots.mapValues { it.value.teamId },
        )
    }

    private fun response(point: CompletionPoint, stored: StoredCompletionDraft?, saving: Boolean = false): PointCompletionDraftResponse {
        val draft = stored?.draft
        val problems = mutableListOf<PointCompletionDiagnostic>()
        if (draft != null && (draft.baseLogRevision != point.match.eventLogRevision ||
                draft.baseLineupRevision != point.lineup?.revision)) {
            problems.add(PointCompletionDiagnostic(DRAFT_BASE_STALE, "The saved draft was based on an earlier journal or lineup"))
        }
        val anchors = point.source.map { it.id }.toSet()
        val insertions = draft?.insertions.orEmpty()
        val usedIds = events.findAllById(insertions.map { it.id }).map { it.id }.toSet()
        val grouped = insertions.filter { insertion ->
            val unsafe = when {
                insertion.afterEventId != null && insertion.afterEventId !in anchors ->
                    ANCHOR_UNAVAILABLE to "The insertion anchor is not an active original event of this point"
                insertion.id in usedIds -> EVENT_ID_IN_USE to "The insertion ID is already used by the journal"
                insertion.fromActorId !in point.actorTeams ||
                    (insertion.toActorId != null && insertion.toActorId !in point.actorTeams) ->
                    ACTOR_UNAVAILABLE to "An actor is not a match participant or an active slot of this point"
                else -> null
            }
            if (unsafe == null) true else {
                if (saving) invalid("Insertion ${insertion.id}: ${unsafe.second}")
                problems.add(PointCompletionDiagnostic(unsafe.first, unsafe.second, insertion.id))
                false
            }
        }.groupBy { it.afterEventId }
        val preview = buildList {
            grouped[null].orEmpty().forEach { add(PointCompletionPreviewEntry(insertion = it)) }
            for (event in point.source) {
                add(PointCompletionPreviewEntry(original = EventResponse.from(event)))
                grouped[event.id].orEmpty().forEach { add(PointCompletionPreviewEntry(insertion = it)) }
            }
        }
        val marker = EventResponse.from(point.marker) as TeamEventResponse
        problems.addAll(validateCompletionPreview(sequencePolicy, point, marker.teamId, preview))
        return PointCompletionDraftResponse(
            point.match.id, point.pointId, marker, point.match.eventLogRevision, point.lineup?.revision,
            stored?.revision ?: 0, draft, preview, problems,
        )
    }

    private fun checkClient(client: PointLineupClient) {
        if (!client.supportsPointResults) throw PointResultClientRequiredException()
    }

    private fun invalid(detail: String): Nothing = reject(INVALID_REQUEST, detail)
    private fun conflict(detail: String): Nothing = reject(POINT_COMPLETION_DRAFT_CONFLICT, detail)
    private fun reject(code: MatchProblemCode, detail: String): Nothing =
        throw PointCompletionDraftRejected(MatchProblem(code, "Point completion draft", detail))
}

class PointCompletionDraftRejected(val problem: MatchProblem) : RuntimeException(problem.detail)

internal data class CompletionPoint(
    val match: Match,
    val pointId: UUID,
    val marker: StoredEvent,
    val source: List<StoredEvent>,
    val firstPoint: Boolean,
    val priorActionTime: Instant?,
    val nextActionTime: Instant?,
    val previousScoringTeam: UUID?,
    val lineup: PointLineup?,
    val roster: List<MatchParticipant>,
    val introduced: Map<UUID, PointSubstitutionSlot>,
    val actorTeams: Map<UUID, UUID>,
)
