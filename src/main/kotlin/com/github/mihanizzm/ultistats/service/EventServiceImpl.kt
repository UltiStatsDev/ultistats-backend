package com.github.mihanizzm.ultistats.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.dto.request.UndoEventRequest
import com.github.mihanizzm.ultistats.dto.response.UndoCommandResponse
import com.github.mihanizzm.ultistats.dto.response.UndoCommandOutcome
import com.github.mihanizzm.ultistats.dto.response.UndoCommandRejection
import com.github.mihanizzm.ultistats.model.UndoCommandReceipt
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataUndoCommandReceiptRepository
import com.github.mihanizzm.ultistats.service.result.UndoCommandResult
import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.response.EventLogResponse
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.factory.EventFactory
import com.github.mihanizzm.ultistats.factory.EventCreationResult
import com.github.mihanizzm.ultistats.model.EventBatchReceipt
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventBatchReceiptRepository
import com.github.mihanizzm.ultistats.service.result.EventBatchResult
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.realtime.MatchRealtimePublisher
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchLifecycleDecision
import com.github.mihanizzm.ultistats.validation.match.MatchLifecyclePolicy
import com.github.mihanizzm.ultistats.validation.event.EventSequenceDecision
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import com.github.mihanizzm.ultistats.validation.event.EventSequenceViolation
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import jakarta.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

@Service
class EventServiceImpl(
    private val eventRepository: SpringDataEventRepository,
    private val matchService: MatchService,
    private val lifecyclePolicy: MatchLifecyclePolicy,
    private val sequencePolicy: EventSequencePolicy,
    private val realtimePublisher: MatchRealtimePublisher,
    private val eventFactory: EventFactory,
    private val receiptRepository: SpringDataEventBatchReceiptRepository,
    private val undoReceipts: SpringDataUndoCommandReceiptRepository,
    private val objectMapper: ObjectMapper,
) : EventService {
    @Transactional
    override fun create(event: Event, matchId: UUID, eventId: UUID?): EventCommandResult {
        val lockedMatch = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val creationFingerprint = event.creationFingerprint()
        eventId?.let { requestedId ->
            eventRepository.findById(requestedId).orElse(null)?.let { existing ->
                // PATCH clears this immutable request fingerprint; DB timestamp rounding must not invalidate retry.
                return if (
                    existing.matchId == matchId &&
                    existing.deletedAt == null &&
                    existing.creationFingerprint == creationFingerprint
                ) {
                    EventCommandResult.Success(existing.toStored(), replayed = true)
                } else {
                    EventCommandResult.Conflict(idempotencyConflict(requestedId))
                }
            }
        }
        return when (val result = appendLocked(lockedMatch, listOf(PendingEvent(eventId ?: UUID.randomUUID(), event)))) {
            is AppendResult.Applied -> EventCommandResult.Success(result.events.single())
            is AppendResult.Rejected -> result.result
        }
    }

    @Transactional
    override fun snapshot(matchId: UUID): EventLogResponse? {
        val match = matchService.getForUpdate(matchId) ?: return null
        return logResponse(match, eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId).map { it.toStored() })
    }

    @Transactional
    override fun appendBatch(matchId: UUID, request: CreateEventBatchRequest): EventBatchResult {
        val match = matchService.getForUpdate(matchId) ?: return EventBatchResult.NotFound
        val lastSequence = lastSequence(matchId)
        fun reject(
            problem: MatchProblem, index: Int? = null, badRequest: Boolean = false,
        ) = EventBatchResult.Rejected(
            problem, lastSequence, match.eventLogRevision,
            index?.let { request.events[it]?.eventId }, index, badRequest,
        )
        fun conflict(code: MatchProblemCode, detail: String, index: Int? = null) =
            reject(MatchProblem(code, "Event batch conflict", detail), index)

        if (request.expectedLastSequenceNumber == null || request.expectedLastSequenceNumber < 0 ||
            request.expectedLogRevision == null || request.expectedLogRevision < 0 ||
            request.events.isEmpty() || request.events.size > 100
        ) return reject(MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid event batch", "Non-negative bases and 1–100 events are required"), badRequest = true)
        val seen = mutableSetOf<UUID>()
        request.events.forEachIndexed { index, event ->
            if (event?.eventId == null || !seen.add(requireNotNull(event.eventId))) {
                return reject(MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid event batch", "Every event requires a unique client eventId"), index, true)
            }
        }
        val pending = request.events.filterNotNull().mapIndexed { index, requestEvent ->
            when (val created = eventFactory.createValidatedFromRequest(requestEvent, matchId)) {
                is EventCreationResult.Success -> PendingEvent(requireNotNull(requestEvent.eventId), created.event)
                is EventCreationResult.Invalid -> return reject(
                    MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid event request", created.detail), index, true,
                )
            }
        }
        val existing = pending.map { eventRepository.findById(it.id).orElse(null) }
        pending.forEachIndexed { index, event ->
            val stored = existing[index]
            if (stored != null && (stored.matchId != matchId || stored.deletedAt != null ||
                    stored.creationFingerprint == null || stored.creationFingerprint != event.event.creationFingerprint())) {
                return reject(idempotencyConflict(event.id), index)
            }
        }
        val fingerprint = sha256(pending.joinToString("|") { "${it.id}:${it.event.creationFingerprint()}" })
        if (existing.any { it != null }) {
            val receipt = receiptRepository.findByMatchIdAndBaseSequenceNumberAndBaseLogRevision(
                matchId, request.expectedLastSequenceNumber, request.expectedLogRevision,
            )
            if (existing.all { it != null } && receipt?.fingerprint == fingerprint &&
                match.lastEventLogInvalidationRevision <= request.expectedLogRevision + 1 &&
                existing.withIndex().all { (index, stored) -> stored!!.sequenceNumber.toLong() == request.expectedLastSequenceNumber.toLong() + index + 1 }
            ) return EventBatchResult.Success(logResponse(match, existing.map { it!!.toStored() }), replayed = true)
            return conflict(MatchProblemCode.EVENT_BATCH_CONFLICT, "The batch is partially applied, differs from its original request, or was invalidated by an edit; reload the canonical event log")
        }
        if (request.expectedLastSequenceNumber != lastSequence || request.expectedLogRevision != match.eventLogRevision) {
            return conflict(MatchProblemCode.EVENT_LOG_STALE, "The expected event log position is stale; reload the canonical event log")
        }
        return when (val appended = appendLocked(match, pending)) {
            is AppendResult.Rejected -> {
                val problem = when (val result = appended.result) {
                    is EventCommandResult.Conflict -> result.problem
                    is EventCommandResult.InvalidState -> result.problem
                    else -> error("Unexpected append rejection")
                }
                reject(problem, appended.index)
            }
            is AppendResult.Applied -> {
                receiptRepository.save(EventBatchReceipt(
                    matchId = matchId,
                    baseSequenceNumber = request.expectedLastSequenceNumber,
                    baseLogRevision = request.expectedLogRevision,
                    fingerprint = fingerprint,
                ))
                EventBatchResult.Success(logResponse(match, appended.events))
            }
        }
    }

    private data class PendingEvent(val id: UUID, val event: Event)
    private sealed interface AppendResult {
        data class Applied(val events: List<StoredEvent>) : AppendResult
        data class Rejected(val result: EventCommandResult, val index: Int) : AppendResult
    }

    /** Shared v1/v2 append pipeline. Validate every prefix before the first write. Caller holds the match lock. */
    private fun appendLocked(match: Match, pending: List<PendingEvent>): AppendResult {
        val active = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(match.id)
        val policyMatch = match.toPolicyMatch(active)
        pending.forEachIndexed { index, command ->
            lifecyclePolicy.validateEventCreation(policyMatch, command.event.occurredAt).toCommandRejection()
                ?.let { return AppendResult.Rejected(it, index) }
            validateSequence(policyMatch.events + command.event)
                ?.let { return AppendResult.Rejected(it, index) }
            policyMatch.events.add(command.event)
        }
        val last = lastSequence(match.id)
        require(last.toLong() + pending.size <= Int.MAX_VALUE) { "Event sequence capacity exceeded" }
        val entities = pending.mapIndexed { index, command ->
            EventEntity.fromDomain(command.id, match.id, last + index + 1, command.event, command.event.creationFingerprint())
        }
        entities.forEach { entity ->
            try {
                eventRepository.insert(entity)
            } catch (exception: DataIntegrityViolationException) {
                throw EventIdempotencyConflictException(entity.id, exception)
            }
        }
        match.eventLogRevision++
        matchService.recalculateScore(match.id)
        realtimePublisher.eventLogChanged(match.id, MatchRealtimeOperation.CREATED, entities.last().id)
        return AppendResult.Applied(entities.map { it.toStored() })
    }

    private fun lastSequence(matchId: UUID): Int =
        eventRepository.findFirstByMatchIdOrderBySequenceNumberDesc(matchId)?.sequenceNumber ?: 0

    private fun logResponse(match: Match, events: List<StoredEvent>) = EventLogResponse(
        events.map(EventResponse::from), lastSequence(match.id), match.eventLogRevision,
    )

    override fun get(eventId: UUID, matchId: UUID): StoredEvent? {
        matchService.getOrThrow(matchId)
        return eventRepository.findByIdAndMatchIdAndDeletedAtIsNull(eventId, matchId)?.toStored()
    }

    @Transactional
    override fun update(eventId: UUID, matchId: UUID, update: (StoredEvent) -> Event): EventCommandResult {
        val match = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val existing = eventRepository.findByIdAndMatchIdAndDeletedAtIsNull(eventId, matchId)
            ?: return EventCommandResult.NotFound
        val event = update(existing.toStored())
        lifecyclePolicy.validateEventUpdate(match).toCommandRejection()?.let { return it }

        require(event.type.canReplace(existing.eventType)) { "Event type can only be refined within the block family" }
        require(event.occurredAt == existing.effectiveOccurredAt) { "Event occurrence time is immutable" }
        val activeEvents = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        val proposedEvents = activeEvents.map { entity ->
            if (entity.id == eventId) event else entity.toDomain()
        }
        validateSequence(proposedEvents, requirePointEnded = match.status == MatchStatus.FINISHED)?.let { return it }

        val updated = EventEntity.fromDomain(
            existing.id,
            matchId,
            existing.sequenceNumber,
            event,
            creationFingerprint = null,
        )
        eventRepository.save(updated)
        match.eventLogRevision++
        match.lastEventLogInvalidationRevision = match.eventLogRevision
        matchService.recalculateScore(matchId)
        realtimePublisher.eventLogChanged(matchId, MatchRealtimeOperation.UPDATED, updated.id)
        return EventCommandResult.Success(updated.toStored())
    }

    @Transactional
    override fun remove(eventId: UUID, matchId: UUID): EventCommandResult {
        val match = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val existing = eventRepository.findByIdAndMatchIdAndDeletedAtIsNull(eventId, matchId)
            ?: return EventCommandResult.NotFound
        return removeLocked(match, existing)
    }

    @Transactional
    override fun undoLast(matchId: UUID): EventCommandResult {
        val match = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val existing = eventRepository.findFirstByMatchIdAndDeletedAtIsNullOrderBySequenceNumberDesc(matchId)
            ?: return EventCommandResult.NotFound
        return when (val result = removeLocked(match, existing)) {
            EventCommandResult.Deleted -> EventCommandResult.Success(existing.toStored())
            else -> result
        }
    }

    @Transactional
    override fun undoOutcome(matchId: UUID, commandId: UUID): UndoCommandResponse? {
        // Serialize lookup with an in-flight command holding this match lock.
        matchService.getForUpdate(matchId) ?: return null
        return undoReceipts.findByMatchIdAndCommandId(matchId, commandId)?.outcome()
    }

    // Jackson receipt encoding can throw checked JsonProcessingException after removeLocked.
    // Any failure must roll back the mutation and the durable outcome together.
    @Transactional(rollbackOn = [Exception::class])
    override fun undo(matchId: UUID, request: UndoEventRequest): UndoCommandResult {
        val commandId = request.commandId
        val expectedTail = request.expectedLastEventId
        val expectedRevision = request.expectedLogRevision
        if (commandId == null || expectedTail == null || expectedRevision == null || expectedRevision < 0) {
            return UndoCommandResult.Invalid(MatchProblem(
                MatchProblemCode.INVALID_REQUEST, "Invalid undo command",
                "commandId, expectedLastEventId and a non-negative expectedLogRevision are required",
            ))
        }
        val match = matchService.getForUpdate(matchId) ?: return UndoCommandResult.NotFound
        undoReceipts.findByMatchIdAndCommandId(matchId, commandId)?.let { receipt ->
            return if (receipt.expectedLastEventId == expectedTail && receipt.expectedLogRevision == expectedRevision) {
                UndoCommandResult.Recorded(receipt.outcome())
            } else {
                UndoCommandResult.Conflict(MatchProblem(
                    MatchProblemCode.UNDO_COMMAND_CONFLICT, "Undo command identity conflict",
                    "This commandId is already bound to a different undo payload; recover the original command outcome",
                ))
            }
        }
        fun record(removed: EventEntity? = null, problem: MatchProblem? = null): UndoCommandResult.Recorded {
            val response = UndoCommandResponse(
                matchId, commandId, expectedTail, expectedRevision,
                if (removed != null) UndoCommandOutcome.APPLIED else UndoCommandOutcome.REJECTED,
                match.eventLogRevision, removed?.id, removed?.sequenceNumber,
                problem?.let { UndoCommandRejection(it.code, it.detail) },
            )
            // Flush failure rolls back deletion, score, revision and AFTER_COMMIT notification too.
            undoReceipts.saveAndFlush(UndoCommandReceipt(
                matchId = matchId, commandId = commandId, expectedLastEventId = expectedTail,
                expectedLogRevision = expectedRevision, resultJson = objectMapper.writeValueAsString(response),
            ))
            return UndoCommandResult.Recorded(response)
        }
        val tail = eventRepository.findFirstByMatchIdAndDeletedAtIsNullOrderBySequenceNumberDesc(matchId)
            ?: return record(problem = MatchProblem(MatchProblemCode.UNDO_LOG_EMPTY, "Empty event log", "There is no active event to undo"))
        if (tail.id != expectedTail || match.eventLogRevision != expectedRevision) {
            return record(problem = MatchProblem(
                MatchProblemCode.EVENT_LOG_STALE, "Stale undo position",
                "The active tail or logRevision differs from the observed undo position",
            ))
        }
        return when (val result = removeLocked(match, tail)) {
            EventCommandResult.Deleted -> record(removed = tail)
            is EventCommandResult.Conflict -> record(problem = result.problem)
            is EventCommandResult.InvalidState -> record(problem = result.problem)
            else -> error("Unexpected result while removing a locked undo tail: $result")
        }
    }

    private fun UndoCommandReceipt.outcome(): UndoCommandResponse =
        objectMapper.readValue(resultJson, UndoCommandResponse::class.java)

    /** Both deletion commands hold the match lock for selection, validation and persistence. */
    private fun removeLocked(match: Match, existing: EventEntity): EventCommandResult {
        val matchId = match.id
        lifecyclePolicy.validateEventDeletion(match).toCommandRejection()?.let { return it }
        val proposedEvents = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
            .filterNot { it.id == existing.id }
            .map { it.toDomain() }
        validateSequence(proposedEvents, requirePointEnded = match.status == MatchStatus.FINISHED)?.let { return it }

        eventRepository.save(existing.copy(deletedAt = Instant.now()))
        match.eventLogRevision++
        match.lastEventLogInvalidationRevision = match.eventLogRevision
        matchService.recalculateScore(matchId)
        realtimePublisher.eventLogChanged(matchId, MatchRealtimeOperation.DELETED, existing.id)
        return EventCommandResult.Deleted
    }

    override fun getAllEventsOfMatch(matchId: UUID): List<StoredEvent> {
        matchService.getOrThrow(matchId)
        return eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId).map { it.toStored() }
    }

    private fun EventEntity.toStored() = StoredEvent(id, sequenceNumber, toDomain())

    private fun Match.toPolicyMatch(activeEvents: List<EventEntity>) = Match(
        id = id,
        teamIds = emptyList(),
        events = activeEvents.map { it.toDomain() }.toMutableList(),
        plannedStartTimestamp = plannedStartTimestamp,
        startedAt = startedAt,
        endedAt = endedAt,
        deletedAt = deletedAt,
    )

    private fun MatchLifecycleDecision.toCommandRejection(): EventCommandResult? = when (this) {
        MatchLifecycleDecision.Allowed -> null
        is MatchLifecycleDecision.InvalidState -> EventCommandResult.InvalidState(problem)
        is MatchLifecycleDecision.Conflict -> EventCommandResult.Conflict(problem)
    }

    private fun validateSequence(
        events: List<Event>,
        requirePointEnded: Boolean = false,
    ): EventCommandResult.Conflict? = when (val decision = sequencePolicy.validate(events, requirePointEnded)) {
        is EventSequenceDecision.Allowed -> null
        is EventSequenceDecision.Rejected -> EventCommandResult.Conflict(decision.violation.toProblem())
    }

    private fun EventSequenceViolation.toProblem() = MatchProblem(
        code = MatchProblemCode.EVENT_SEQUENCE_VIOLATION,
        title = "Event sequence conflict",
        detail = attemptedType?.let { "$it is not allowed while the event log is in state $currentState" }
            ?: "A finished match event log must end at a completed point; current state is $currentState",
        currentState = currentState,
        attemptedEventType = attemptedType,
    )

    private fun idempotencyConflict(eventId: UUID) = MatchProblem(
        code = MatchProblemCode.EVENT_IDEMPOTENCY_CONFLICT,
        title = "Event idempotency conflict",
        detail = "Event $eventId already exists with a different payload",
    )

    private fun Event.creationFingerprint(): String {
        val canonicalPayload = when (this) {
            is com.github.mihanizzm.ultistats.model.events.OnePlayerEvent ->
                listOf(type, occurredAt, participant)
            is com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent ->
                listOf(type, occurredAt, fromParticipant, toParticipant)
            is com.github.mihanizzm.ultistats.model.events.TeamEvent ->
                listOf(type, occurredAt, team)
            is com.github.mihanizzm.ultistats.model.events.SystemEvent ->
                listOf(type, occurredAt)
        }.joinToString("|")
        return sha256(canonicalPayload)
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )
}

class EventIdempotencyConflictException(
    val eventId: UUID,
    cause: Throwable,
) : RuntimeException("Event $eventId already exists", cause)
