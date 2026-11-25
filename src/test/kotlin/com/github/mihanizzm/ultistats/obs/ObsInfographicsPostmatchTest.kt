package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.dto.response.statistics.AnnouncementSummaryResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.AnnouncementTotalsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.DefenseStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchTimeStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamAttackStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamTimeStatisticsResponse
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObsInfographicsPostmatchTest {
    private val matchId = UUID(0, 1)
    private val left = UUID(0, 2)
    private val right = UUID(0, 3)
    private val horizon = Instant.parse("2026-11-26T12:34:56Z")

    @Test
    fun `projects six canonical counters in selected team order and preserves event horizon`() {
        val home = team(left, complete = 5, all = 7, defense = DefenseStatisticsResponse(2, 3, 4, 4, 1), breaks = 2)
        val away = team(right)
        val panel = project(away, home)
        assertEquals(horizon, panel.statsAsOfEventAt)
        assertEquals(listOf(left, right), panel.teams.map { it.teamId })
        assertEquals(ObsPostmatchMetrics(5, 2, 9, 4, 1, 2), panel.teams[0].metrics)
        assertEquals(5.0 / 7, panel.teams[0].accuracy)
        assertEquals(ObsPostmatchMetrics(0, 0, 0, 0, 0, 0), panel.teams[1].metrics)
        assertNull(panel.teams[1].accuracy)
    }

    @Test
    fun `uses combined canonical possession with real one sided zero and no all zero shares`() {
        val examples = listOf(
            Triple(360_000L, 240_000L, listOf(0.6, 0.4)),
            Triple(1L, 2L, listOf(1.0 / 3, 2.0 / 3)),
            Triple(0L, 5L, listOf(0.0, 1.0)),
            Triple(0L, 0L, listOf(null, null)),
            Triple(1L, -1L, listOf(null, null)),
        )
        for ((home, away, fractions) in examples) {
            val panel = project(team(left, possession = home), team(right, possession = away))
            assertEquals(fractions, panel.teams.map { it.possession })
            assertEquals(home.takeIf { it >= 0 }, panel.teams[0].possessionTimeMs)
        }
    }

    @Test
    fun `invalid derived counters and absent legacy team are unavailable independently`() {
        val invalid = team(left, complete = 7, all = 5, defense = DefenseStatisticsResponse(-1, 2, 3, 0, 0), breaks = -1)
        val panel = project(invalid)
        assertNull(panel.teams[0].accuracy)
        assertNull(panel.teams[0].possession)
        assertEquals(ObsPostmatchMetrics(7, null, null, 0, 0, null), panel.teams[0].metrics)
        assertEquals(ObsPostmatchMetrics(null, null, null, null, null, null), panel.teams[1].metrics)
        assertNull(panel.teams[1].possessionTimeMs)
        assertEquals(0.0, project(team(left, all = 2), team(right)).teams[0].accuracy)

        val max = Int.MAX_VALUE
        val largeBlocks = project(team(left, defense = DefenseStatisticsResponse(max, max, max, 0, 0)))
        assertEquals(6_442_450_941L, largeBlocks.teams[0].metrics.blocks)
    }

    private fun project(vararg teams: TeamStatisticsResponse) = ObsInfographicsPostmatchService.project(
        listOf(left, right), MatchStatisticsResponse(
            matchId, teams.toList(), MatchTimeStatisticsResponse(800_000, 11, 22, 33, 799_934),
            AnnouncementSummaryResponse(0, 0, emptyList(), AnnouncementTotalsResponse(0, 0)),
        ), horizon,
    )

    private fun team(
        id: UUID, possession: Long = 0, complete: Int = 0, all: Int = 0,
        defense: DefenseStatisticsResponse = DefenseStatisticsResponse(0, 0, 0, 0, 0), breaks: Int = 0,
    ) = TeamStatisticsResponse(
        id, "Команда", TeamAttackStatisticsResponse(0, complete, all, 0, 0, 0, breaks),
        defense, TeamTimeStatisticsResponse(possession, 0, 0), emptyList(),
    )
}
