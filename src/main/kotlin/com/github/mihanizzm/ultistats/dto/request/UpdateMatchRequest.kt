package com.github.mihanizzm.ultistats.dto.request

import java.time.Instant
import java.util.UUID

/**
 * DTO для частичного обновления матча.
 * Все поля необязательны — обновляются только переданные поля.
 */
data class UpdateMatchRequest(
    val teamIds: List<UUID>? = null,
    val playersPerTeam: Int? = null,
    val plannedStartTimestamp: Instant? = null,
)
