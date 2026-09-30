package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.request.OnePlayerEventPatchRequest
import com.github.mihanizzm.ultistats.dto.request.OnePlayerEventRequest
import com.github.mihanizzm.ultistats.dto.request.TeamEventPatchRequest
import com.github.mihanizzm.ultistats.dto.request.TeamEventRequest
import com.github.mihanizzm.ultistats.dto.request.TwoPlayerEventPatchRequest
import com.github.mihanizzm.ultistats.dto.request.TwoPlayerEventRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateEventRequest
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.factory.EventFactory
import com.github.mihanizzm.ultistats.factory.EventCreationResult
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.springframework.stereotype.Component
import java.util.UUID

sealed class EventResult {
    data class Success(val response: EventResponse) : EventResult()
    data class EventList(val events: List<EventResponse>) : EventResult()
    object Deleted : EventResult()
    object NotFound : EventResult()
    data class BadRequest(val problem: MatchProblem) : EventResult()
    object MethodNotAllowed : EventResult()
    data class InvalidState(val problem: MatchProblem) : EventResult()
    data class Conflict(val problem: MatchProblem) : EventResult()
}

@Component
class EventFacade(
    private val eventService: EventService,
    private val matchService: MatchService,
    private val eventFactory: EventFactory,
) {
    fun getAll(matchId: UUID): EventResult {
        if (matchService.get(matchId) == null) return EventResult.NotFound
        return EventResult.EventList(eventService.getAllEventsOfMatch(matchId).map(EventResponse::from))
    }

    fun get(matchId: UUID, eventId: UUID): EventResult {
        if (matchService.get(matchId) == null) return EventResult.NotFound
        return eventService.get(eventId, matchId)?.let { EventResult.Success(EventResponse.from(it)) }
            ?: EventResult.NotFound
    }

    fun create(matchId: UUID, request: CreateEventRequest): EventResult {
        if (matchService.get(matchId) == null) return EventResult.NotFound
        return when (val creation = eventFactory.createValidatedFromRequest(request, matchId)) {
            is EventCreationResult.Success -> eventService.create(creation.event, matchId).toFacadeResult()
            is EventCreationResult.Invalid -> badRequest(creation.detail)
        }
    }

    fun edit(matchId: UUID, eventId: UUID, request: UpdateEventRequest): EventResult {
        if (matchService.get(matchId) == null) return EventResult.NotFound
        return try {
            eventService.update(eventId, matchId) { stored -> mergeUpdate(stored, request, matchId) }.toFacadeResult()
        } catch (exception: InvalidEventUpdateException) {
            badRequest(requireNotNull(exception.message))
        } catch (_: UnsupportedEventUpdateException) {
            EventResult.MethodNotAllowed
        }
    }

    private fun mergeUpdate(
        stored: StoredEvent,
        request: UpdateEventRequest,
        matchId: UUID,
    ): Event {
        if (!request.type.canReplace(stored.event.type)) {
            throw InvalidEventUpdateException("Event type ${request.type} cannot replace ${stored.event.type}")
        }
        val merged: CreateEventRequest = when {
            stored.event is OnePlayerEvent && request is OnePlayerEventPatchRequest ->
                OnePlayerEventRequest(stored.event.type, stored.event.occurredAt, request.participantId)
            stored.event is TwoPlayerEvent && request is TwoPlayerEventPatchRequest ->
                TwoPlayerEventRequest(
                    request.type,
                    stored.event.occurredAt,
                    request.fromParticipantId ?: stored.event.fromParticipant,
                    request.toParticipantId ?: stored.event.toParticipant,
                )
            stored.event is TeamEvent && request is TeamEventPatchRequest ->
                TeamEventRequest(stored.event.type, stored.event.occurredAt, request.teamId)
            stored.event is SystemEvent -> throw UnsupportedEventUpdateException()
            else -> throw InvalidEventUpdateException("Event patch shape does not match the stored event")
        }
        return when (val creation = eventFactory.createValidatedFromRequest(merged, matchId)) {
            is EventCreationResult.Success -> creation.event
            is EventCreationResult.Invalid -> throw InvalidEventUpdateException(creation.detail)
        }
    }

    fun delete(matchId: UUID, eventId: UUID): EventResult {
        if (matchService.get(matchId) == null) return EventResult.NotFound
        return eventService.remove(eventId, matchId).toFacadeResult()
    }

    private fun EventCommandResult.toFacadeResult(): EventResult = when (this) {
        is EventCommandResult.Success -> EventResult.Success(EventResponse.from(event))
        EventCommandResult.Deleted -> EventResult.Deleted
        EventCommandResult.NotFound -> EventResult.NotFound
        is EventCommandResult.InvalidState -> EventResult.InvalidState(problem)
        is EventCommandResult.Conflict -> EventResult.Conflict(problem)
    }

    private fun badRequest(detail: String) = EventResult.BadRequest(
        MatchProblem(
            code = MatchProblemCode.INVALID_REQUEST,
            title = "Invalid event request",
            detail = detail,
        ),
    )
}

private class InvalidEventUpdateException(message: String) : RuntimeException(message)
private class UnsupportedEventUpdateException : RuntimeException()
