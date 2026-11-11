package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.UndoEventRequest
import com.github.mihanizzm.ultistats.dto.response.UndoCommandResponse
import com.github.mihanizzm.ultistats.service.result.UndoCommandResult
import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.response.EventLogResponse
import com.github.mihanizzm.ultistats.service.result.EventBatchResult
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import java.util.UUID

interface EventService {
    fun undo(matchId: UUID, request: UndoEventRequest, pointLineupCapable: Boolean = false): UndoCommandResult
    fun undoOutcome(matchId: UUID, commandId: UUID): UndoCommandResponse?

    fun appendBatch(matchId: UUID, request: CreateEventBatchRequest, pointLineupCapable: Boolean = false): EventBatchResult
    fun snapshot(matchId: UUID, pointLineupCapable: Boolean = false): EventLogResponse?

    fun create(event: Event, matchId: UUID, eventId: UUID? = null): EventCommandResult
    fun create(matchId: UUID, request: CreateEventRequest, pointLineupCapable: Boolean): EventCommandResult
    fun get(eventId: UUID, matchId: UUID, pointLineupCapable: Boolean = false): StoredEvent?
    fun update(eventId: UUID, matchId: UUID, pointLineupCapable: Boolean = false, update: (StoredEvent) -> Event): EventCommandResult
    fun remove(eventId: UUID, matchId: UUID, pointLineupCapable: Boolean = false): EventCommandResult
    fun undoLast(matchId: UUID, pointLineupCapable: Boolean = false): EventCommandResult
    fun getAllEventsOfMatch(matchId: UUID, pointLineupCapable: Boolean = false): List<StoredEvent>
}
