package com.github.mihanizzm.ultistats.dto.request

import java.time.Instant
import java.util.UUID

data class CreateMatchRequest(
    val teamIds: List<UUID>,
    val playersPerTeam: Int,
    val plannedStartTimestamp: Instant? = null,
)
