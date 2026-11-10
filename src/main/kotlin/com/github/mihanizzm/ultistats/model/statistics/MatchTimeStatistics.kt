package com.github.mihanizzm.ultistats.model.statistics

import java.time.Duration
import java.util.UUID

data class MatchTimeStatistics(
    val totalTime: Duration = Duration.ZERO,
    val timeSpentBetweenPoints: Duration = Duration.ZERO,
    val timeSpentOnTimeouts: Duration = Duration.ZERO,
    val timeSpentOnHalftime: Duration = Duration.ZERO,
    val pureGameTime: Duration = Duration.ZERO,
    val announcements: Map<UUID?, AnnouncementStatistics> = emptyMap(),
)

data class AnnouncementStatistics(
    val count: Int = 0,
    val duration: Duration = Duration.ZERO,
)
