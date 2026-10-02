package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(OnePlayerEventRequest::class, name = "INCOMPLETE_PASS"),
    JsonSubTypes.Type(OnePlayerEventRequest::class, name = "PULL"),
    JsonSubTypes.Type(OnePlayerEventRequest::class, name = "BRICK"),
    JsonSubTypes.Type(OnePlayerEventRequest::class, name = "PICKUP"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "PASS"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "GOAL"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "BLOCK"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "BLOCK_MARKER"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "BLOCK_FIELD"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "INTERCEPTION"),
    JsonSubTypes.Type(TwoPlayerEventRequest::class, name = "CALLAHAN"),
    JsonSubTypes.Type(TeamEventRequest::class, name = "TIMEOUT_START"),
    JsonSubTypes.Type(TeamEventRequest::class, name = "TIMEOUT_END"),
    JsonSubTypes.Type(SystemEventRequest::class, name = "HALFTIME_START"),
    JsonSubTypes.Type(SystemEventRequest::class, name = "HALFTIME_END"),
)
@Schema(
    oneOf = [OnePlayerEventRequest::class, TwoPlayerEventRequest::class, TeamEventRequest::class, SystemEventRequest::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "INCOMPLETE_PASS", schema = OnePlayerEventRequest::class),
        DiscriminatorMapping(value = "PULL", schema = OnePlayerEventRequest::class),
        DiscriminatorMapping(value = "BRICK", schema = OnePlayerEventRequest::class),
        DiscriminatorMapping(value = "PICKUP", schema = OnePlayerEventRequest::class),
        DiscriminatorMapping(value = "PASS", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "GOAL", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "BLOCK", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "BLOCK_MARKER", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "BLOCK_FIELD", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "INTERCEPTION", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "CALLAHAN", schema = TwoPlayerEventRequest::class),
        DiscriminatorMapping(value = "TIMEOUT_START", schema = TeamEventRequest::class),
        DiscriminatorMapping(value = "TIMEOUT_END", schema = TeamEventRequest::class),
        DiscriminatorMapping(value = "HALFTIME_START", schema = SystemEventRequest::class),
        DiscriminatorMapping(value = "HALFTIME_END", schema = SystemEventRequest::class),
    ],
)
sealed interface CreateEventRequest {
    @get:Schema(
        description = "Тип события. Он определяет форму запроса и является обязательным.",
        example = "PASS",
    )
    val type: EventType

    @get:Schema(
        description = "Фактическое время события в формате ISO-8601 UTC.",
        example = "2026-07-28T12:30:00Z",
    )
    val occurredAt: Instant
}

@Schema(description = "Событие с одним участником матча: INCOMPLETE_PASS, PULL, BRICK или PICKUP.")
data class OnePlayerEventRequest(
    @field:Schema(
        description = "Тип события с одним участником.",
        allowableValues = ["INCOMPLETE_PASS", "PULL", "BRICK", "PICKUP"],
        example = "PICKUP",
    )
    override val type: EventType,
    @field:Schema(
        description = "Фактическое время события в формате ISO-8601 UTC.",
        example = "2026-07-28T12:30:00Z",
    )
    override val occurredAt: Instant,
    @field:Schema(
        description = "ID участника-снэпшота этого матча: бросающего для INCOMPLETE_PASS, выполнившего пулл для PULL/BRICK или подобравшего диск для PICKUP.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val participantId: UUID,
) : CreateEventRequest

@Schema(description = "Событие с двумя участниками матча: PASS, GOAL, BLOCK, BLOCK_MARKER, BLOCK_FIELD, INTERCEPTION или CALLAHAN.")
data class TwoPlayerEventRequest(
    @field:Schema(
        description = "Тип события с двумя участниками.",
        allowableValues = ["PASS", "GOAL", "BLOCK", "BLOCK_MARKER", "BLOCK_FIELD", "INTERCEPTION", "CALLAHAN"],
        example = "PASS",
    )
    override val type: EventType,
    @field:Schema(
        description = "Фактическое время события в формате ISO-8601 UTC.",
        example = "2026-07-28T12:30:00Z",
    )
    override val occurredAt: Instant,
    @field:Schema(
        description = "ID бросающего участника-снэпшота этого матча.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val fromParticipantId: UUID,
    @field:Schema(
        description = "ID участника-снэпшота, который принял пас или сыграл в защите. Для PASS/GOAL он из команды бросающего; для BLOCK/INTERCEPTION/CALLAHAN — из противоположной команды.",
        example = "22222222-2222-2222-2222-222222222222",
    )
    val toParticipantId: UUID,
) : CreateEventRequest

@Schema(description = "Командное событие: TIMEOUT_START или TIMEOUT_END.")
data class TeamEventRequest(
    @field:Schema(
        description = "Тип командного события.",
        allowableValues = ["TIMEOUT_START", "TIMEOUT_END"],
        example = "TIMEOUT_START",
    )
    override val type: EventType,
    @field:Schema(
        description = "Фактическое время события в формате ISO-8601 UTC.",
        example = "2026-07-28T12:30:00Z",
    )
    override val occurredAt: Instant,
    @field:Schema(
        description = "ID команды-снэпшота этого матча, которая начинает или завершает таймаут.",
        example = "33333333-3333-3333-3333-333333333333",
    )
    val teamId: UUID,
) : CreateEventRequest

@Schema(description = "Системное событие: HALFTIME_START или HALFTIME_END.")
data class SystemEventRequest(
    @field:Schema(
        description = "Тип системного события. Дополнительные идентификаторы не передаются.",
        allowableValues = ["HALFTIME_START", "HALFTIME_END"],
        example = "HALFTIME_START",
    )
    override val type: EventType,
    @field:Schema(
        description = "Фактическое время события в формате ISO-8601 UTC.",
        example = "2026-07-28T12:30:00Z",
    )
    override val occurredAt: Instant,
) : CreateEventRequest
