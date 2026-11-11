package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import java.util.UUID

interface StatisticsAggregator {
    fun aggregate(
        previousStatisticsState: MatchStatistics,
        events: List<Event>,
        teamByParticipantId: Map<UUID, UUID>,
        announcements: List<MatchAnnouncement> = emptyList(),
    ): MatchStatistics
}
