package com.github.mihanizzm.ultistats.dto.response.statistics

import io.swagger.v3.oas.annotations.media.Schema

data class PlayerTimeStatisticsResponse(
    val possessionTimeMs: Long,
    @field:Schema(description = "Mean active duration of completed player holds in milliseconds. Excludes timeouts and unfinished holds. Zero is a legacy placeholder when averagePossessionTimeAvailable is false.")
    val averagePossessionTimeMs: Long,
    @field:Schema(description = "True when at least one completed hold exists, including a measured zero-duration hold. Consumers must check this before displaying the average.", requiredMode = Schema.RequiredMode.REQUIRED)
    val averagePossessionTimeAvailable: Boolean = false,
)
