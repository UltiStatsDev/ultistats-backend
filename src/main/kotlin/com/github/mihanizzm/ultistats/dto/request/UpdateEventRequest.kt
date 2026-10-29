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
    description = "Исправление участников или команды существующего события. occurredAt неизменяем и не входит в PATCH. Редактирование HALFTIME_START/HALFTIME_END не поддерживается (405).",
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
sealed interface UpdateEventRequest {
    @get:Schema(
        description = "Обязательный тип события. Должен совпадать с сохранённым; смена разрешена только между BLOCK, BLOCK_MARKER и BLOCK_FIELD в любом направлении. Остальные смены возвращают 400.",
        example = "BLOCK_MARKER",
    )
    val type: EventType
}

@Schema(description = "Замена единственного участника INCOMPLETE_PASS, PULL, BRICK или PICKUP. Обязательны type и participantId.")
data class OnePlayerEventPatchRequest(
    override val type: EventType,
    @field:Schema(
        description = "Обязательный UUID нового участника из состава матча, включая UNKNOWN. Роли как в POST: INCOMPLETE_PASS — бросающий; PULL/BRICK — выполнивший пулл; PICKUP — подобравший диск.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val participantId: UUID,
) : UpdateEventRequest

@Schema(description = "Частичное исправление двух участников или уточнение типа блока. Обязателен type; отсутствующий или null идентификатор сохраняет прежнего участника. Можно передать только type для изменения разновидности блока.")
data class TwoPlayerEventPatchRequest(
    override val type: EventType,
    @field:Schema(
        description = "Новый UUID бросающего участника матча, включая UNKNOWN. Для PASS/GOAL — автор паса/ассиста, для блоков и перехватов — бросающий соперник. Отсутствие или null сохраняет прежнее значение. Итоговые участники должны различаться.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val fromParticipantId: UUID? = null,
    @field:Schema(
        description = "Новый UUID принимающего участника для PASS/GOAL (та же команда) либо защитника для BLOCK/BLOCK_MARKER/BLOCK_FIELD/INTERCEPTION/CALLAHAN (противоположная команда). Допустим UNKNOWN из состава матча. Отсутствие или null сохраняет прежнее значение.",
        example = "22222222-2222-2222-2222-222222222222",
    )
    val toParticipantId: UUID? = null,
) : UpdateEventRequest

@Schema(description = "Замена команды события TIMEOUT_START или TIMEOUT_END. Обязательны type и teamId; тип начала/окончания таймаута менять нельзя.")
data class TeamEventPatchRequest(
    override val type: EventType,
    @field:Schema(
        description = "Обязательный UUID новой команды-участницы матча, которой принадлежит таймаут. Это teamId, а не participantId.",
        example = "33333333-3333-3333-3333-333333333333",
    )
    val teamId: UUID,
) : UpdateEventRequest

@Schema(description = "Системные события HALFTIME_START/HALFTIME_END не редактируются: запрос с сохранённым type возвращает 405 Method Not Allowed.")
data class SystemEventPatchRequest(override val type: EventType) : UpdateEventRequest
