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
        description = "Необязательный UUID события, заранее сгенерированный клиентом. Повтор POST с тем же eventId и тем же payload возвращает исходное активное неизменённое событие без дубликата; другой payload, а также повтор после PATCH или DELETE возвращает 409. Если поле не передано, UUID генерирует сервер.",
        example = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    val eventId: UUID?

    @get:Schema(
        description = "Обязательный тип события. Определяет схему и роли участников; выберите соответствующий пример POST.",
        example = "PASS",
    )
    val type: EventType

    @get:Schema(
        description = "Фактическое время события в ISO-8601 UTC. Не раньше начала матча и последнего активного события; одинаковое время допустимо. После создания не изменяется.",
        example = "2026-07-28T12:30:00Z",
    )
    val occurredAt: Instant
}

@Schema(description = "Событие с одним участником матча: INCOMPLETE_PASS, PULL, BRICK или PICKUP.")
data class OnePlayerEventRequest(
    override val type: EventType,
    override val occurredAt: Instant,
    @field:Schema(
        description = "UUID участника из состава этого матча (participantId, включая UNKNOWN). INCOMPLETE_PASS — бросающий, чей пас не завершён; PULL — выполнивший пулл; BRICK — выполнивший предшествующий пулл, а не объявивший брик; PICKUP — подобравший диск.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val participantId: UUID,
    override val eventId: UUID? = null,
) : CreateEventRequest

@Schema(description = "Событие с двумя участниками матча: PASS, GOAL, BLOCK, BLOCK_MARKER, BLOCK_FIELD, INTERCEPTION или CALLAHAN.")
data class TwoPlayerEventRequest(
    override val type: EventType,
    override val occurredAt: Instant,
    @field:Schema(
        description = "UUID бросающего участника из состава матча (participantId, включая UNKNOWN). Для PASS/GOAL — автор паса/ассиста; для BLOCK, BLOCK_MARKER, BLOCK_FIELD, INTERCEPTION, CALLAHAN — бросающий соперник защитника. Должен отличаться от toParticipantId.",
        example = "11111111-1111-1111-1111-111111111111",
    )
    val fromParticipantId: UUID,
    @field:Schema(
        description = "UUID второго участника из состава матча (participantId, включая UNKNOWN). Для PASS/GOAL — принимающий/автор гола из той же команды; для BLOCK, BLOCK_MARKER, BLOCK_FIELD — блокирующий защитник; для INTERCEPTION/CALLAHAN — перехвативший защитник. Защитник должен быть из противоположной команды.",
        example = "22222222-2222-2222-2222-222222222222",
    )
    val toParticipantId: UUID,
    override val eventId: UUID? = null,
) : CreateEventRequest

@Schema(description = "Командное событие: TIMEOUT_START или TIMEOUT_END.")
data class TeamEventRequest(
    override val type: EventType,
    override val occurredAt: Instant,
    @field:Schema(
        description = "UUID команды-участницы этого матча, чей таймаут начинается (TIMEOUT_START) или заканчивается (TIMEOUT_END). Это teamId, а не participantId.",
        example = "33333333-3333-3333-3333-333333333333",
    )
    val teamId: UUID,
    override val eventId: UUID? = null,
) : CreateEventRequest

@Schema(description = "Системное событие: HALFTIME_START — начало перерыва после завершённого розыгрыша; HALFTIME_END — окончание перерыва, после которого ожидается PULL. Требуются только type и occurredAt; участники и команда не передаются.")
data class SystemEventRequest(
    override val type: EventType,
    override val occurredAt: Instant,
    override val eventId: UUID? = null,
) : CreateEventRequest
