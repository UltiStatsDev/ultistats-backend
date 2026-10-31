package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/**
 * DTO для частичного обновления матча.
 * Все поля необязательны — обновляются только переданные поля.
 */
data class UpdateMatchRequest(
    @field:Schema(types = ["array", "null"])
    val teamIds: List<UUID>? = null,
    @field:Schema(types = ["integer", "null"])
    val playersPerTeam: Int? = null,
    @field:Schema(types = ["string", "null"], format = "date-time")
    val plannedStartTimestamp: Instant? = null,
)
