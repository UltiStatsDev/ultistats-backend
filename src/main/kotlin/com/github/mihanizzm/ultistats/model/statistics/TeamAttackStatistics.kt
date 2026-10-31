package com.github.mihanizzm.ultistats.model.statistics

data class TeamAttackStatistics(
    val score: Int = 0,
    val completePasses: Int = 0,
    val allPasses: Int = 0,
    val pulls: Int = 0,
    val bricks: Int = 0,
    val possessions: Int = 0,
    val breaks: Int = 0,
)
