package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import java.util.UUID

interface EventService {
    fun create(event: Event, matchId: UUID, eventId: UUID? = null): EventCommandResult
    fun get(eventId: UUID, matchId: UUID): StoredEvent?
    fun update(eventId: UUID, matchId: UUID, update: (StoredEvent) -> Event): EventCommandResult
    fun remove(eventId: UUID, matchId: UUID): EventCommandResult
    fun undoLast(matchId: UUID): EventCommandResult
    fun getAllEventsOfMatch(matchId: UUID): List<StoredEvent>
}
