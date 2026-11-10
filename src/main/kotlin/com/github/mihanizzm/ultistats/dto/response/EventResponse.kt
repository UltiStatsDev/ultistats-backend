package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.events.AnnouncementStartEvent
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(
    oneOf = [OnePlayerEventResponse::class, TwoPlayerEventResponse::class, TeamEventResponse::class, SystemEventResponse::class, AnnouncementStartResponse::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "INCOMPLETE_PASS", schema = OnePlayerEventResponse::class),
        DiscriminatorMapping(value = "PULL", schema = OnePlayerEventResponse::class),
        DiscriminatorMapping(value = "BRICK", schema = OnePlayerEventResponse::class),
        DiscriminatorMapping(value = "PICKUP", schema = OnePlayerEventResponse::class),
        DiscriminatorMapping(value = "PASS", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "GOAL", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "BLOCK", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "BLOCK_MARKER", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "BLOCK_FIELD", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "INTERCEPTION", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "CALLAHAN", schema = TwoPlayerEventResponse::class),
        DiscriminatorMapping(value = "TIMEOUT_START", schema = TeamEventResponse::class),
        DiscriminatorMapping(value = "TIMEOUT_END", schema = TeamEventResponse::class),
        DiscriminatorMapping(value = "HALFTIME_START", schema = SystemEventResponse::class),
        DiscriminatorMapping(value = "HALFTIME_END", schema = SystemEventResponse::class),
        DiscriminatorMapping(value = "ANNOUNCEMENT_START", schema = AnnouncementStartResponse::class),
        DiscriminatorMapping(value = "ANNOUNCEMENT_END", schema = SystemEventResponse::class),
    ],
)
sealed interface EventResponse {
    val id: UUID
    val sequenceNumber: Int
    val type: EventType
    val occurredAt: Instant

    companion object {
        fun from(stored: StoredEvent): EventResponse = when (val event = stored.event) {
            is OnePlayerEvent -> OnePlayerEventResponse(
                stored.id, stored.sequenceNumber, event.type, event.occurredAt, event.participant,
            )
            is TwoPlayerEvent -> TwoPlayerEventResponse(
                stored.id, stored.sequenceNumber, event.type, event.occurredAt,
                event.fromParticipant, event.toParticipant,
            )
            is TeamEvent -> TeamEventResponse(stored.id, stored.sequenceNumber, event.type, event.occurredAt, event.team)
            is AnnouncementStartEvent ->
                AnnouncementStartResponse(stored.id, stored.sequenceNumber, event.type, event.occurredAt, event.team)
            is SystemEvent -> SystemEventResponse(stored.id, stored.sequenceNumber, event.type, event.occurredAt)
        }
    }
}

data class OnePlayerEventResponse(
    override val id: UUID,
    override val sequenceNumber: Int,
    override val type: EventType,
    override val occurredAt: Instant,
    val participantId: UUID,
) : EventResponse

data class TwoPlayerEventResponse(
    override val id: UUID,
    override val sequenceNumber: Int,
    override val type: EventType,
    override val occurredAt: Instant,
    val fromParticipantId: UUID,
    val toParticipantId: UUID,
) : EventResponse

data class TeamEventResponse(
    override val id: UUID,
    override val sequenceNumber: Int,
    override val type: EventType,
    override val occurredAt: Instant,
    val teamId: UUID,
) : EventResponse

data class AnnouncementStartResponse(
    override val id: UUID,
    override val sequenceNumber: Int,
    override val type: EventType,
    override val occurredAt: Instant,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(description = "Команда из снимка матча или null.", types = ["string", "null"], format = "uuid",
        requiredMode = Schema.RequiredMode.REQUIRED)
    val teamId: UUID?,
) : EventResponse

data class SystemEventResponse(
    override val id: UUID,
    override val sequenceNumber: Int,
    override val type: EventType,
    override val occurredAt: Instant,
) : EventResponse
