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
    fun undo(matchId: UUID, request: UndoEventRequest, client: PointLineupClient = PointLineupClient.LEGACY): UndoCommandResult
    fun undoOutcome(matchId: UUID, commandId: UUID): UndoCommandResponse?

    fun appendBatch(matchId: UUID, request: CreateEventBatchRequest, client: PointLineupClient = PointLineupClient.LEGACY): EventBatchResult
    fun snapshot(matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY): EventLogResponse?

    fun create(event: Event, matchId: UUID, eventId: UUID? = null): EventCommandResult
    fun create(matchId: UUID, request: CreateEventRequest, client: PointLineupClient): EventCommandResult
    fun get(eventId: UUID, matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY): StoredEvent?
    fun update(eventId: UUID, matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY, update: (StoredEvent) -> Event): EventCommandResult
    fun remove(eventId: UUID, matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY): EventCommandResult
    fun undoLast(matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY): EventCommandResult
    fun getAllEventsOfMatch(matchId: UUID, client: PointLineupClient = PointLineupClient.LEGACY): List<StoredEvent>
}
