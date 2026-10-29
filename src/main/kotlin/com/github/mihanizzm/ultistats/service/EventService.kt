package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.response.EventLogResponse
import com.github.mihanizzm.ultistats.service.result.EventBatchResult
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import java.util.UUID

interface EventService {
    fun appendBatch(matchId: UUID, request: CreateEventBatchRequest): EventBatchResult
    fun snapshot(matchId: UUID): EventLogResponse?

    fun create(event: Event, matchId: UUID, eventId: UUID? = null): EventCommandResult
    fun get(eventId: UUID, matchId: UUID): StoredEvent?
    fun update(eventId: UUID, matchId: UUID, update: (StoredEvent) -> Event): EventCommandResult
    fun remove(eventId: UUID, matchId: UUID): EventCommandResult
    fun undoLast(matchId: UUID): EventCommandResult
    fun getAllEventsOfMatch(matchId: UUID): List<StoredEvent>
}
