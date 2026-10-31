package com.github.mihanizzm.ultistats.model.statistics

import java.time.Duration

data class PlayerTimeStatistics(
    val totalPossessionTime: Duration = Duration.ZERO,
    val averagePossessionTime: Duration = Duration.ZERO,
    val averagePossessionTimeAvailable: Boolean = false,
)
