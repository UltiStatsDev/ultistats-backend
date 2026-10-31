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
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.statistics.DefenseStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchTimeStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerAttackStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerTimeStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamAttackStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamTimeStatistics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class StatisticsResponseMapperTest {
    private val mapper = StatisticsResponseMapper()

    @Test
    fun `maps statistics by identifiers and preserves match ordering`() {
        val response = mapper.toResponse(match(), reversedStatistics())

        assertThat(response).isEqualTo(
            MatchStatisticsResponse(
                matchId = MATCH_ID,
                teams = listOf(
                    TeamStatisticsResponse(
                        teamId = TEAM_1_ID,
                        teamName = "Flying Bears",
                        attack = TeamAttackStatisticsResponse(
                            score = 7,
                            completePasses = 31,
                            allPasses = 37,
                            pulls = 8,
                            bricks = 2,
                            possessions = 14,
                        ),
                        defense = DefenseStatisticsResponse(
                            blocks = 3,
                            blocksAsMarker = 1,
                            blocksAsFieldPlayer = 2,
                            interceptions = 4,
                            callahans = 1,
                        ),
                        time = TeamTimeStatisticsResponse(
                            possessionTimeMs = 42_000L,
                            betweenPointsTimeMs = 12_000L,
                            timeoutTimeMs = 3_000L,
                        ),
                        participants = listOf(
                            ParticipantStatisticsResponse(
                                participantId = PLAYER_ID,
                                kind = MatchParticipantKind.PLAYER,
                                unknownSlot = null,
                                firstName = "Ivan",
                                lastName = "Ivanov",
                                displayName = "Ivan Ivanov",
                                number = 17,
                                attack = PlayerAttackStatisticsResponse(
                                    passes = 12,
                                    catches = 11,
                                    assists = 3,
                                    goals = 2,
                                    dropsOnMarker = 1,
                                    dropsOnField = 2,
                                    incompletePasses = 4,
                                    callahanDrops = 1,
                                    discPossessions = 15,
                                    pulls = 5,
                                    bricks = 1,
                                ),
                                defense = DefenseStatisticsResponse(
                                    blocks = 2,
                                    blocksAsMarker = 1,
                                    blocksAsFieldPlayer = 1,
                                    interceptions = 3,
                                    callahans = 1,
                                ),
                                time = PlayerTimeStatisticsResponse(
                                    possessionTimeMs = 21_000L,
                                    averagePossessionTimeMs = 1_500L,
                                    averagePossessionTimeAvailable = true,
                                ),
                            ),
                            ParticipantStatisticsResponse(
                                participantId = UNKNOWN_ID,
                                kind = MatchParticipantKind.UNKNOWN,
                                unknownSlot = 1,
                                firstName = null,
                                lastName = null,
                                displayName = "Неизвестный игрок 1",
                                number = null,
                                attack = PlayerAttackStatisticsResponse(
                                    passes = 6,
                                    catches = 5,
                                    assists = 1,
                                    goals = 1,
                                    dropsOnMarker = 2,
                                    dropsOnField = 3,
                                    incompletePasses = 4,
                                    callahanDrops = 0,
                                    discPossessions = 9,
                                    pulls = 2,
                                    bricks = 1,
                                ),
                                defense = DefenseStatisticsResponse(
                                    blocks = 1,
                                    blocksAsMarker = 0,
                                    blocksAsFieldPlayer = 1,
                                    interceptions = 2,
                                    callahans = 0,
                                ),
                                time = PlayerTimeStatisticsResponse(
                                    possessionTimeMs = 9_000L,
                                    averagePossessionTimeMs = 1_000L,
                                    averagePossessionTimeAvailable = true,
                                ),
                            ),
                        ),
                    ),
                    TeamStatisticsResponse(
                        teamId = TEAM_2_ID,
                        teamName = "Swift Foxes",
                        attack = TeamAttackStatisticsResponse(
                            score = 5,
                            completePasses = 24,
                            allPasses = 29,
                            pulls = 6,
                            bricks = 1,
                            possessions = 12,
                        ),
                        defense = DefenseStatisticsResponse(
                            blocks = 2,
                            blocksAsMarker = 1,
                            blocksAsFieldPlayer = 1,
                            interceptions = 2,
                            callahans = 0,
                        ),
                        time = TeamTimeStatisticsResponse(
                            possessionTimeMs = 36_000L,
                            betweenPointsTimeMs = 10_000L,
                            timeoutTimeMs = 2_000L,
                        ),
                        participants = emptyList(),
                    ),
                ),
                time = MatchTimeStatisticsResponse(
                    totalTimeMs = 90_000L,
                    betweenPointsTimeMs = 22_000L,
                    timeoutTimeMs = 5_000L,
                    halftimeTimeMs = 10_000L,
                    pureGameTimeMs = 53_000L,
                ),
            ),
        )
    }

    @Test
    fun `fails with participant identifier when participant statistics are missing`() {
        val statistics = reversedStatistics().copy(
            playerStatistics = reversedStatistics().playerStatistics.filterNot { it.participantId == PLAYER_ID },
        )

        assertThatIllegalStateException()
            .isThrownBy { mapper.toResponse(match(), statistics) }
            .withMessageContaining(PLAYER_ID.toString())
    }

    @Test
    fun `fails with team identifier when team statistics are missing`() {
        val statistics = reversedStatistics().copy(
            teamStatistics = reversedStatistics().teamStatistics.filterNot { it.teamId == TEAM_1_ID },
        )

        assertThatIllegalStateException()
            .isThrownBy { mapper.toResponse(match(), statistics) }
            .withMessageContaining(TEAM_1_ID.toString())
    }

    @Test
    fun `fails with display snapshot evidence when team name is missing`() {
        val match = match().copy(teamNamesById = mapOf(TEAM_2_ID to "Swift Foxes"))

        assertThatIllegalStateException()
            .isThrownBy { mapper.toResponse(match, reversedStatistics()) }
            .withMessageContaining("Display snapshot missing")
            .withMessageContaining(TEAM_1_ID.toString())
    }

    private fun match() = Match(
        id = MATCH_ID,
        teamIds = listOf(TEAM_1_ID, TEAM_2_ID),
        participantsByTeam = mapOf(
            TEAM_1_ID to listOf(
                MatchParticipant.player(
                    matchId = MATCH_ID,
                    teamId = TEAM_1_ID,
                    playerId = PLAYER_ID,
                    firstName = "Ivan",
                    lastName = "Ivanov",
                    number = 17,
                ),
                MatchParticipant(
                    matchId = MATCH_ID,
                    participantId = UNKNOWN_ID,
                    teamId = TEAM_1_ID,
                    kind = MatchParticipantKind.UNKNOWN,
                    unknownSlot = 1,
                ),
            ),
            TEAM_2_ID to emptyList(),
        ),
        teamNamesById = mapOf(
            TEAM_1_ID to "Flying Bears",
            TEAM_2_ID to "Swift Foxes",
        ),
    )

    private fun reversedStatistics() = MatchStatistics(
        playerStatistics = listOf(
            PlayerStatistics(
                participantId = UNKNOWN_ID,
                attack = PlayerAttackStatistics(
                    passes = 6,
                    catches = 5,
                    assists = 1,
                    goals = 1,
                    dropsOnMarker = 2,
                    dropsOnField = 3,
                    incompletePasses = 4,
                    callahanDrops = 0,
                    discPossessions = 9,
                    pulls = 2,
                    bricks = 1,
                ),
                defense = DefenseStatistics(
                    blocks = 1,
                    blocksAsMarker = 0,
                    blocksAsFieldPlayer = 1,
                    interceptions = 2,
                    callahans = 0,
                ),
                time = PlayerTimeStatistics(
                    totalPossessionTime = Duration.ofSeconds(9),
                    averagePossessionTime = Duration.ofSeconds(1),
                    averagePossessionTimeAvailable = true,
                ),
            ),
            PlayerStatistics(
                participantId = PLAYER_ID,
                attack = PlayerAttackStatistics(
                    passes = 12,
                    catches = 11,
                    assists = 3,
                    goals = 2,
                    dropsOnMarker = 1,
                    dropsOnField = 2,
                    incompletePasses = 4,
                    callahanDrops = 1,
                    discPossessions = 15,
                    pulls = 5,
                    bricks = 1,
                ),
                defense = DefenseStatistics(
                    blocks = 2,
                    blocksAsMarker = 1,
                    blocksAsFieldPlayer = 1,
                    interceptions = 3,
                    callahans = 1,
                ),
                time = PlayerTimeStatistics(
                    totalPossessionTime = Duration.ofSeconds(21),
                    averagePossessionTime = Duration.ofMillis(1_500),
                    averagePossessionTimeAvailable = true,
                ),
            ),
        ),
        teamStatistics = listOf(
            TeamStatistics(
                teamId = TEAM_2_ID,
                attack = TeamAttackStatistics(
                    score = 5,
                    completePasses = 24,
                    allPasses = 29,
                    pulls = 6,
                    bricks = 1,
                    possessions = 12,
                ),
                defense = DefenseStatistics(
                    blocks = 2,
                    blocksAsMarker = 1,
                    blocksAsFieldPlayer = 1,
                    interceptions = 2,
                    callahans = 0,
                ),
                time = TeamTimeStatistics(
                    totalPossessionTime = Duration.ofSeconds(36),
                    totalTimeBetweenPoints = Duration.ofSeconds(10),
                    totalTimeSpentOnTimeouts = Duration.ofSeconds(2),
                ),
            ),
            TeamStatistics(
                teamId = TEAM_1_ID,
                attack = TeamAttackStatistics(
                    score = 7,
                    completePasses = 31,
                    allPasses = 37,
                    pulls = 8,
                    bricks = 2,
                    possessions = 14,
                ),
                defense = DefenseStatistics(
                    blocks = 3,
                    blocksAsMarker = 1,
                    blocksAsFieldPlayer = 2,
                    interceptions = 4,
                    callahans = 1,
                ),
                time = TeamTimeStatistics(
                    totalPossessionTime = Duration.ofSeconds(42),
                    totalTimeBetweenPoints = Duration.ofSeconds(12),
                    totalTimeSpentOnTimeouts = Duration.ofSeconds(3),
                ),
            ),
        ),
        timeStatistics = MatchTimeStatistics(
            totalTime = Duration.ofSeconds(90),
            timeSpentBetweenPoints = Duration.ofSeconds(22),
            timeSpentOnTimeouts = Duration.ofSeconds(5),
            timeSpentOnHalftime = Duration.ofSeconds(10),
            pureGameTime = Duration.ofSeconds(53),
        ),
    )

    private companion object {
        val MATCH_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val TEAM_1_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val TEAM_2_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val PLAYER_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val UNKNOWN_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000002")
    }
}
