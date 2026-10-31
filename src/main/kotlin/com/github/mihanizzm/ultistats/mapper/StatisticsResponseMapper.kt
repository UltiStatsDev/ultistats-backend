package com.github.mihanizzm.ultistats.mapper

import com.github.mihanizzm.ultistats.dto.response.statistics.DefenseStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchTimeStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.ParticipantStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.PlayerAttackStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.PlayerTimeStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamAttackStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamTimeStatisticsResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipant
import com.github.mihanizzm.ultistats.model.statistics.DefenseStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchTimeStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerAttackStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerTimeStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamAttackStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamTimeStatistics
import org.springframework.stereotype.Component

@Component
class StatisticsResponseMapper {
    fun toResponse(match: Match, statistics: MatchStatistics): MatchStatisticsResponse {
        val teamsById = statistics.teamStatistics.associateBy { it.teamId }
        val participantsById = statistics.playerStatistics.associateBy { it.participantId }

        return MatchStatisticsResponse(
            matchId = match.id,
            teams = match.teamIds.map { teamId ->
                val team = checkNotNull(teamsById[teamId]) {
                    "Statistics missing for team $teamId"
                }
                TeamStatisticsResponse(
                    teamId = teamId,
                    teamName = checkNotNull(match.teamNamesById[teamId]) {
                        "Display snapshot missing for team $teamId"
                    },
                    attack = team.attack.toResponse(),
                    defense = team.defense.toResponse(),
                    time = team.time.toResponse(),
                    participants = match.participantsByTeam[teamId].orEmpty().map { participant ->
                        participant.toResponse(
                            checkNotNull(participantsById[participant.participantId]) {
                                "Statistics missing for participant ${participant.participantId}"
                            },
                        )
                    },
                )
            },
            time = statistics.timeStatistics.toResponse(),
        )
    }

    private fun MatchParticipant.toResponse(statistics: PlayerStatistics) = ParticipantStatisticsResponse(
        participantId = participantId,
        kind = kind,
        unknownSlot = unknownSlot,
        firstName = firstName,
        lastName = lastName,
        displayName = displayName,
        number = number,
        attack = statistics.attack.toResponse(),
        defense = statistics.defense.toResponse(),
        time = statistics.time.toResponse(),
    )

    private fun TeamAttackStatistics.toResponse() = TeamAttackStatisticsResponse(
        score = score,
        completePasses = completePasses,
        allPasses = allPasses,
        pulls = pulls,
        bricks = bricks,
        possessions = possessions,
        breaks = breaks,
    )

    private fun PlayerAttackStatistics.toResponse() = PlayerAttackStatisticsResponse(
        passes = passes,
        catches = catches,
        assists = assists,
        goals = goals,
        dropsOnMarker = dropsOnMarker,
        dropsOnField = dropsOnField,
        incompletePasses = incompletePasses,
        callahanDrops = callahanDrops,
        discPossessions = discPossessions,
        pulls = pulls,
        bricks = bricks,
    )

    private fun DefenseStatistics.toResponse() = DefenseStatisticsResponse(
        blocks = blocks,
        blocksAsMarker = blocksAsMarker,
        blocksAsFieldPlayer = blocksAsFieldPlayer,
        interceptions = interceptions,
        callahans = callahans,
    )

    private fun MatchTimeStatistics.toResponse() = MatchTimeStatisticsResponse(
        totalTimeMs = totalTime.toMillis(),
        betweenPointsTimeMs = timeSpentBetweenPoints.toMillis(),
        timeoutTimeMs = timeSpentOnTimeouts.toMillis(),
        halftimeTimeMs = timeSpentOnHalftime.toMillis(),
        pureGameTimeMs = pureGameTime.toMillis(),
    )

    private fun TeamTimeStatistics.toResponse() = TeamTimeStatisticsResponse(
        possessionTimeMs = totalPossessionTime.toMillis(),
        betweenPointsTimeMs = totalTimeBetweenPoints.toMillis(),
        timeoutTimeMs = totalTimeSpentOnTimeouts.toMillis(),
    )

    private fun PlayerTimeStatistics.toResponse() = PlayerTimeStatisticsResponse(
        possessionTimeMs = totalPossessionTime.toMillis(),
        averagePossessionTimeMs = averagePossessionTime.toMillis(),
    )
}
