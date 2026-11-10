package com.github.mihanizzm.ultistats.model.events

import java.time.Instant
import java.util.UUID

data class AnnouncementStartEvent(
    val team: UUID?,
    override val occurredAt: Instant,
    override val type: EventType = EventType.ANNOUNCEMENT_START,
) : Event {
    init {
        require(type == EventType.ANNOUNCEMENT_START)
    }
}
