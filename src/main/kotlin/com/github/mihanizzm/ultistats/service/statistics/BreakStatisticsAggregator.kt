package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import org.springframework.stereotype.Component
import java.util.UUID

/** Rebuilds breaks from the current canonical log, using match participant snapshot identities. */
@Component
class BreakStatisticsAggregator : StatisticsAggregator {
    override fun aggregateObservations(
        previousStatisticsState: MatchStatistics,
        events: List<StatisticsEvent>,
        teamByParticipantId: Map<UUID, UUID>,
        announcements: List<MatchAnnouncement>,
    ): MatchStatistics {
        var pullingTeam: UUID? = null
        val breaksByTeam = mutableMapOf<UUID, Int>()
        for (event in events) {
            when {
                event.type == EventType.PULL ->
                    pullingTeam = teamByParticipantId.getValue(event.fromActor)
                event.scoresPoint -> {
                    val scoringTeam = event.teamId ?: teamByParticipantId.getValue(event.toActor)
                    if (scoringTeam == pullingTeam) {
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
