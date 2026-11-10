package com.github.mihanizzm.ultistats.model.events

import java.time.Instant

/**
 * Системное событие.
 * Используется для: HALFTIME_START, HALFTIME_END, ANNOUNCEMENT_END.
 */
data class SystemEvent(
    override val occurredAt: Instant,
    override val type: EventType,
) : Event {
    init {
        require(type.category == EventCategory.SYSTEM) {
            "EventType $type не является SYSTEM событием"
        }
    }
}
