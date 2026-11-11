package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import org.springframework.stereotype.Service

@Service
@Suppress("unused")
class StatisticsServiceImpl(
    private val statisticsAggregatorList: List<StatisticsAggregator>,
    private val announcements: MatchAnnouncementRepository,
    private val pointLineups: PointLineupRepository,
) : StatisticsService {
    override fun recalculateMatchStatistics(match: Match): MatchStatistics {
        val teamByParticipantId = match.participantsByTeam.flatMap { (teamId, participants) ->
            participants.map { it.participantId to teamId }
        }.toMap() + pointLineups.slots(match.id).mapValues { it.value.teamId }
        val initialStatistics = MatchStatistics(
            playerStatistics = teamByParticipantId.keys.map { PlayerStatistics(participantId = it) },
            teamStatistics = match.teamIds.map { TeamStatistics(teamId = it) },
        )

        // Attribution for personal statistics can project these events in memory.
        // The raw journal and the scoreboard's team attribution stay independent.
        val events = match.events
        val intervals = announcements.list(match.id)
        return statisticsAggregatorList.fold(initialStatistics) { stats, aggregator ->
            aggregator.aggregate(stats, events, teamByParticipantId, intervals)
        }
    }
}
