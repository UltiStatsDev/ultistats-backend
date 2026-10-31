package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class CreateMatchRequest(
    val teamIds: List<UUID>,
    val playersPerTeam: Int,
    @field:Schema(types = ["string", "null"], format = "date-time")
    val plannedStartTimestamp: Instant? = null,
)
