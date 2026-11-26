package com.github.mihanizzm.ultistats.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.dto.request.PutPointCompletionApprovalRequest
import com.github.mihanizzm.ultistats.dto.request.PutPointCompletionDraftRequest
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionApprovalResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnostic
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnosticCode.*
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraft
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraftResponse
import com.github.mihanizzm.ultistats.dto.response.TeamEventResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.realtime.MatchRealtimePublisher
import com.github.mihanizzm.ultistats.repository.jpa.ApprovalReceipt
import com.github.mihanizzm.ultistats.repository.jpa.PointCompletionApprovalRepository
import com.github.mihanizzm.ultistats.repository.jpa.StoredPointApproval
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
    private val approvals: PointCompletionApprovalRepository,
    private val history: PointCompletionHistoryService,
    private val json: ObjectMapper,
    private val realtime: MatchRealtimePublisher,
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
        checkUnapproved(matchId, pointId)
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

    @Transactional(rollbackFor = [Exception::class])
    fun approve(matchId: UUID, pointId: UUID, request: PutPointCompletionApprovalRequest,
                client: PointLineupClient): PointCompletionApprovalResponse? {
        checkClient(client)
        val match = matches.findByIdForUpdate(matchId) ?: return null
        approvals.findReceipt(matchId, request.operationId)?.let { receipt ->
            if (receipt.response.pointId != pointId || receipt.response.closingEventId != request.expectedClosingEventId ||
                receipt.expectedLogRevision != request.expectedLogRevision ||
                receipt.expectedLineupRevision != request.expectedLineupRevision ||
                receipt.response.draftRevision != request.expectedDraftRevision) {
                reject(EVENT_IDEMPOTENCY_CONFLICT, "This operationId already identifies a different approval request")
            }
            return receipt.response
        }
        if (!writesEnabled) reject(POINT_RESULT_WRITE_DISABLED, "Point result writes are disabled")
        if (request.expectedLogRevision < 0 || request.expectedDraftRevision <= 0 ||
            (request.expectedLineupRevision ?: 0) < 0) invalid("Supply a positive saved draft revision and nonnegative source revisions")
        checkUnapproved(matchId, pointId)
        if (match.eventLogRevision != request.expectedLogRevision) reject(EVENT_LOG_STALE, "The event log revision has changed")
        val point = snapshot(match, pointId)
        if (request.expectedClosingEventId != point.marker.id) conflict("The closing event has changed")
        if (request.expectedLineupRevision != point.lineup?.revision) {
            reject(POINT_COMPLETION_DRAFT_STALE, "The point lineup revision has changed")
        }
        val stored = currentDraft(point)
        if (stored == null || stored.revision != request.expectedDraftRevision) {
            reject(POINT_COMPLETION_DRAFT_STALE, "Reload the saved draft revision")
        }
        val candidate = response(point, stored)
        if (candidate.diagnostics.isNotEmpty()) {
            throw PointCompletionDraftRejected(MatchProblem(
                POINT_COMPLETION_APPROVAL_CONFLICT, "Point completion approval conflict",
                "Resolve the candidate diagnostics before approval",
            ), candidate.diagnostics)
        }

        val accepted = PointCompletionApprovalResponse(
            request.operationId, pointId, point.marker.id, stored.revision, match.eventLogRevision + 1,
            requireNotNull(events.findFirstByMatchIdOrderBySequenceNumberDesc(matchId)).sequenceNumber, replayed = false,
        )
        approvals.insert(matchId, StoredPointApproval(pointId, point.marker.id, json.writeValueAsString(stored.draft.insertions)))
        approvals.insertReceipt(matchId, ApprovalReceipt(request.expectedLogRevision, request.expectedLineupRevision, accepted))
        match.eventLogRevision = accepted.logRevision
        match.lastEventLogInvalidationRevision = accepted.logRevision
        realtime.matchChanged(matchId)
        return accepted
    }

    private fun checkUnapproved(matchId: UUID, pointId: UUID) {
        if (approvals.exists(matchId, pointId)) reject(POINT_COMPLETION_APPROVAL_LOCKED, "The point already has an approved completion")
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
        val teamIds = matchTeams.findAllByMatchIdOrderByPosition(match.id).map { it.teamId }
        return completionPoint(match, point, journal, participants.findAllByMatchId(match.id),
            lineups.list(match.id, teamIds)[pointId], lineups.introducedSlots(match.id), lineups.slots(match.id))
            ?: conflict("Only points closed by an active POINT_RESULT can be completed")
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
        val usedIds = if (insertions.isEmpty()) emptySet() else {
            events.findAllByMatchIdAndIdIn(point.match.id, insertions.map { it.id }).mapTo(mutableSetOf()) { it.id } +
                history.restoredIds(point.match.id)
        }
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
        val preview = completionPreview(point, grouped)
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

class PointCompletionDraftRejected(
    val problem: MatchProblem,
    val diagnostics: List<PointCompletionDiagnostic> = emptyList(),
) : RuntimeException(problem.detail)
