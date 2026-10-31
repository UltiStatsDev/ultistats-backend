package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
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
    ): MatchStatistics {
        var pullingTeam: UUID? = null
        val breaksByTeam = mutableMapOf<UUID, Int>()
        for (event in events) {
            when (event.type) {
                EventType.PULL -> pullingTeam = teamByParticipantId.getValue((event as OnePlayerEvent).participant)
                EventType.GOAL, EventType.CALLAHAN -> {
                    // GOAL's receiver and CALLAHAN's defender are both the second actor.
                    val scoringTeam = teamByParticipantId.getValue((event as TwoPlayerEvent).toParticipant)
                    if (scoringTeam == pullingTeam) {
                        breaksByTeam[scoringTeam] = breaksByTeam.getOrDefault(scoringTeam, 0) + 1
                    }
                    pullingTeam = null
                }
                EventType.HALFTIME_START, EventType.HALFTIME_END -> pullingTeam = null
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
