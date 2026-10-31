package com.github.mihanizzm.ultistats.dto.response.statistics

import io.swagger.v3.oas.annotations.media.Schema

data class TeamAttackStatisticsResponse(
    val score: Int,
    val completePasses: Int,
    val allPasses: Int,
    val pulls: Int,
    val bricks: Int,
    val possessions: Int,
    @field:Schema(
        description = "Goals scored by the team that pulled in the same point, including CALLAHAN",
        minimum = "0",
        example = "1",
    )
    val breaks: Int = 0,
)
