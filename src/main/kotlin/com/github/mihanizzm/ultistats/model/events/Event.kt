package com.github.mihanizzm.ultistats.model.events

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_eventClass")
@JsonSubTypes(
    JsonSubTypes.Type(value = OnePlayerEvent::class, name = "OnePlayerEvent"),
    JsonSubTypes.Type(value = TwoPlayerEvent::class, name = "TwoPlayerEvent"),
    JsonSubTypes.Type(value = TeamEvent::class, name = "TeamEvent"),
    JsonSubTypes.Type(value = SystemEvent::class, name = "SystemEvent"),
    JsonSubTypes.Type(value = AnnouncementStartEvent::class, name = "AnnouncementStartEvent"),
)
sealed interface Event {
    val occurredAt: Instant
    val type: EventType
}
