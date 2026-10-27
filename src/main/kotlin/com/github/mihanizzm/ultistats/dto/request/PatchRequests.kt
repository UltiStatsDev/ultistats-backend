package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import org.openapitools.jackson.nullable.JsonNullable
import java.time.Instant
import java.util.UUID

@Schema(
    description = "Пропущенные поля не изменяются. Явный null для имени или фамилии недопустим.",
    example = """{"firstName":"Иван"}""",
)
data class PatchPlayerRequest(
    @field:Schema(
        description = "Новое имя. Поле можно пропустить; null недопустим.",
        example = "Иван",
    )
    val firstName: JsonNullable<String> = JsonNullable.undefined(),
    @field:Schema(
        description = "Новая фамилия. Поле можно пропустить; null недопустим.",
        example = "Петров",
    )
    val lastName: JsonNullable<String> = JsonNullable.undefined(),
)

@Schema(
    description = "Пропущенные поля не изменяются. Явный null очищает город, но недопустим для названия.",
    example = """{"city":null}""",
)
data class PatchTeamRequest(
    @field:Schema(
        description = "Новое название. Поле можно пропустить; null недопустим.",
        example = "Соколы",
    )
    val name: JsonNullable<String> = JsonNullable.undefined(),
    @field:Schema(
        description = "Новый город. Поле можно пропустить; null очищает город.",
        example = "Новосибирск",
        types = ["string", "null"],
    )
    val city: JsonNullable<String> = JsonNullable.undefined(),
)

@Schema(
    description = "Пропущенные поля не изменяются. Явный null очищает плановое время, но недопустим для команд и числа игроков.",
    example = """{"playersPerTeam":7,"plannedStartTimestamp":null}""",
)
data class PatchMatchRequest(
    @field:Schema(
        description = "Ровно два UUID разных активных команд. Поле можно пропустить; null недопустим.",
        example = """["11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222"]""",
    )
    val teamIds: JsonNullable<List<UUID>> = JsonNullable.undefined(),
    @field:Schema(
        description = "Число игроков от каждой команды. Минимум 2; null недопустим.",
        example = "7",
    )
    val playersPerTeam: JsonNullable<Int> = JsonNullable.undefined(),
    @field:Schema(
        description = "Новое плановое время. Поле можно пропустить; null очищает плановое время.",
        example = "2026-08-14T10:00:00Z",
        types = ["string", "null"],
        format = "date-time",
    )
    val plannedStartTimestamp: JsonNullable<Instant> = JsonNullable.undefined(),
)
