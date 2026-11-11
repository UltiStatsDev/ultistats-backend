package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import org.springframework.stereotype.Service

@Service
@Suppress("unused")
class StatisticsServiceImpl(
    private val statisticsAggregatorList: List<StatisticsAggregator>,
    private val announcements: MatchAnnouncementRepository,
) : StatisticsService {
    override fun recalculateMatchStatistics(match: Match): MatchStatistics {
        val initialStatistics = MatchStatistics(
            playerStatistics = match.participantsByTeam.values.flatten()
                .map { PlayerStatistics(participantId = it.participantId) },
            teamStatistics = match.teamIds.map { TeamStatistics(teamId = it) },
        )
        val teamByParticipantId = match.participantsByTeam.flatMap { (teamId, participants) ->
            participants.map { it.participantId to teamId }
        }.toMap()

        val intervals = announcements.list(match.id)
        return statisticsAggregatorList.fold(initialStatistics) { stats, aggregator ->
            aggregator.aggregate(stats, match.events, teamByParticipantId, intervals)
        }
    }
}
