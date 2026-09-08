package com.github.mihanizzm.ultistats.service

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
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class EventServiceImpl(
    private val eventRepository: SpringDataEventRepository,
    private val matchService: MatchService,
    private val lifecyclePolicy: MatchLifecyclePolicy,
    private val sequencePolicy: EventSequencePolicy,
    private val realtimePublisher: MatchRealtimePublisher,
) : EventService {
    @Transactional
    override fun create(event: Event, matchId: UUID): EventCommandResult {
        val lockedMatch = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val activeEvents = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        val match = lockedMatch.toPolicyMatch(activeEvents)
        lifecyclePolicy.validateEventCreation(match, event.occurredAt).toCommandRejection()?.let { return it }
        validateSequence(activeEvents.map { it.toDomain() } + event)?.let { return it }

        val lastInSequence = eventRepository.findFirstByMatchIdOrderBySequenceNumberDesc(matchId)
        val entity = EventEntity.fromDomain(UUID.randomUUID(), matchId, (lastInSequence?.sequenceNumber ?: 0) + 1, event)
        eventRepository.save(entity)
        matchService.recalculateScore(matchId)
        realtimePublisher.eventLogChanged(matchId, MatchRealtimeOperation.CREATED, entity.id)
        return EventCommandResult.Success(entity.toStored())
    }

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
        require(event.occurredAt == existing.occurredAt) { "Event occurrence time is immutable" }
        val activeEvents = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
        val proposedEvents = activeEvents.map { entity ->
            if (entity.id == eventId) event else entity.toDomain()
        }
        validateSequence(proposedEvents, requirePointEnded = match.status == MatchStatus.FINISHED)?.let { return it }

        val updated = EventEntity.fromDomain(existing.id, matchId, existing.sequenceNumber, event)
        eventRepository.save(updated)
        matchService.recalculateScore(matchId)
        realtimePublisher.eventLogChanged(matchId, MatchRealtimeOperation.UPDATED, updated.id)
        return EventCommandResult.Success(updated.toStored())
    }

    @Transactional
    override fun remove(eventId: UUID, matchId: UUID): EventCommandResult {
        val match = matchService.getForUpdate(matchId) ?: return EventCommandResult.NotFound
        val existing = eventRepository.findByIdAndMatchIdAndDeletedAtIsNull(eventId, matchId)
            ?: return EventCommandResult.NotFound
        lifecyclePolicy.validateEventDeletion(match).toCommandRejection()?.let { return it }
        val proposedEvents = eventRepository.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
            .filterNot { it.id == eventId }
            .map { it.toDomain() }
        validateSequence(proposedEvents, requirePointEnded = match.status == MatchStatus.FINISHED)?.let { return it }

        eventRepository.save(existing.copy(deletedAt = Instant.now()))
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
}
