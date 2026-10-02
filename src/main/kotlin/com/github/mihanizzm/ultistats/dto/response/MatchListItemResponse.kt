package com.github.mihanizzm.ultistats.dto.response

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.Team
import java.time.Instant
import java.util.UUID

data class MatchListItemResponse(
    val id: UUID,
    val teams: List<MatchListTeamResponse>,
    val playersPerTeam: Int,
    val plannedStartTimestamp: Instant?,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val status: MatchStatus,
) {
    companion object {
        fun from(match: Match, teamsById: Map<UUID, Team>): MatchListItemResponse =
            MatchListItemResponse(
                id = match.id,
                teams = match.teamIds.mapNotNull { teamId ->
                    teamsById[teamId]?.let { team ->
                        val teamScore = match.teamScores.find { it.teamId == teamId }?.score ?: 0
                        MatchListTeamResponse(
                            teamId = team.id,
                            teamName = match.teamNamesById.getValue(teamId),
                            teamScore = teamScore,
                            city = team.city,
                            photoUrl = team.photoUrl,
                        )
                    }
                },
                playersPerTeam = match.playersPerTeam,
                plannedStartTimestamp = match.plannedStartTimestamp,
                startedAt = match.startedAt,
                endedAt = match.endedAt,
                status = match.status,
            )
    }
}
