package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.scoringTeam
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import org.springframework.stereotype.Component
import java.util.UUID

/** Rebuilds breaks from the current canonical log, using match participant snapshot identities. */
@Component
class BreakStatisticsAggregator : StatisticsAggregator {
    override fun aggregate(
        previousStatisticsState: MatchStatistics,
        events: List<Event>,
        teamByParticipantId: Map<UUID, UUID>,
        announcements: List<MatchAnnouncement>,
    ): MatchStatistics {
        var pullingTeam: UUID? = null
        val breaksByTeam = mutableMapOf<UUID, Int>()
        for (event in events) {
            when {
                event.type == EventType.PULL ->
                    pullingTeam = teamByParticipantId.getValue((event as OnePlayerEvent).participant)
                event.type.closesPoint -> {
                    val scoringTeam = event.scoringTeam(teamByParticipantId::getValue)
                    if (scoringTeam != null && scoringTeam == pullingTeam) {
                        breaksByTeam.merge(scoringTeam, 1, Int::plus)
                    }
                    pullingTeam = null
                }
                event.type == EventType.HALFTIME_START || event.type == EventType.HALFTIME_END -> pullingTeam = null
                else -> Unit
            }
        }
        return previousStatisticsState.copy(
            teamStatistics = previousStatisticsState.teamStatistics.map { team ->
                team.copy(attack = team.attack.copy(breaks = breaksByTeam.getOrDefault(team.teamId, 0)))
            },
        )
    }
}
