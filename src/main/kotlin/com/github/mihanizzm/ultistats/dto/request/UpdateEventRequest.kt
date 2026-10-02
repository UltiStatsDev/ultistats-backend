package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes(
    JsonSubTypes.Type(OnePlayerEventPatchRequest::class, name = "INCOMPLETE_PASS"),
    JsonSubTypes.Type(OnePlayerEventPatchRequest::class, name = "PULL"),
    JsonSubTypes.Type(OnePlayerEventPatchRequest::class, name = "BRICK"),
    JsonSubTypes.Type(OnePlayerEventPatchRequest::class, name = "PICKUP"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "PASS"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "GOAL"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "BLOCK"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "BLOCK_MARKER"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "BLOCK_FIELD"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "INTERCEPTION"),
    JsonSubTypes.Type(TwoPlayerEventPatchRequest::class, name = "CALLAHAN"),
    JsonSubTypes.Type(TeamEventPatchRequest::class, name = "TIMEOUT_START"),
    JsonSubTypes.Type(TeamEventPatchRequest::class, name = "TIMEOUT_END"),
    JsonSubTypes.Type(SystemEventPatchRequest::class, name = "HALFTIME_START"),
    JsonSubTypes.Type(SystemEventPatchRequest::class, name = "HALFTIME_END"),
)
@Schema(
    oneOf = [OnePlayerEventPatchRequest::class, TwoPlayerEventPatchRequest::class, TeamEventPatchRequest::class],
    discriminatorProperty = "type",
    discriminatorMapping = [
        DiscriminatorMapping(value = "INCOMPLETE_PASS", schema = OnePlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "PULL", schema = OnePlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "BRICK", schema = OnePlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "PICKUP", schema = OnePlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "PASS", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "GOAL", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "BLOCK", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "BLOCK_MARKER", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "BLOCK_FIELD", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "INTERCEPTION", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "CALLAHAN", schema = TwoPlayerEventPatchRequest::class),
        DiscriminatorMapping(value = "TIMEOUT_START", schema = TeamEventPatchRequest::class),
        DiscriminatorMapping(value = "TIMEOUT_END", schema = TeamEventPatchRequest::class),
    ],
)
sealed interface UpdateEventRequest { val type: EventType }

@Schema(description = "Исправление события с одним участником.")
data class OnePlayerEventPatchRequest(
    @field:Schema(
        description = "Существующий тип события с одним участником.",
        allowableValues = ["INCOMPLETE_PASS", "PULL", "BRICK", "PICKUP"],
        example = "PICKUP",
    )
    override val type: EventType,
    @field:Schema(
        description = "Новый ID участника-снэпшота этого матча.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val participantId: UUID,
) : UpdateEventRequest

@Schema(description = "Исправление события с двумя участниками. Можно передать один или оба идентификатора.")
data class TwoPlayerEventPatchRequest(
    @field:Schema(
        description = "Существующий тип события либо другой тип блока для уточнения BLOCK/BLOCK_MARKER/BLOCK_FIELD.",
        allowableValues = ["PASS", "GOAL", "BLOCK", "BLOCK_MARKER", "BLOCK_FIELD", "INTERCEPTION", "CALLAHAN"],
        example = "PASS",
    )
    override val type: EventType,
    @field:Schema(
        description = "Новый ID бросающего. Если поле отсутствует, прежнее значение сохраняется.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val fromParticipantId: UUID? = null,
    @field:Schema(
        description = "Новый ID принимающего или защитника. Если поле отсутствует, прежнее значение сохраняется.",
        example = "22222222-2222-2222-2222-222222222222",
    )
    val toParticipantId: UUID? = null,
) : UpdateEventRequest

@Schema(description = "Исправление команды в событии таймаута.")
data class TeamEventPatchRequest(
    @field:Schema(
        description = "Существующий тип события таймаута.",
        allowableValues = ["TIMEOUT_START", "TIMEOUT_END"],
        example = "TIMEOUT_START",
    )
    override val type: EventType,
    @field:Schema(
        description = "Новый ID команды-снэпшота этого матча.",
        example = "33333333-3333-3333-3333-333333333333",
    )
    val teamId: UUID,
) : UpdateEventRequest

data class SystemEventPatchRequest(override val type: EventType) : UpdateEventRequest
