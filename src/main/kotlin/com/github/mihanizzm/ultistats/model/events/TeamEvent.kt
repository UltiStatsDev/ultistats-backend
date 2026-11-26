package com.github.mihanizzm.ultistats.model.events

import java.time.Instant
import java.util.UUID

/**
 * Событие команды.
 * Используется для: TIMEOUT_START, TIMEOUT_END, POINT_RESULT.
 */
data class TeamEvent(
    val team: UUID,
    override val occurredAt: Instant,
    override val type: EventType,
) : Event {
    init {
        require(type.category == EventCategory.TEAM) {
            "EventType $type не является TEAM событием"
        }
    }
}
