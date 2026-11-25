package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.dto.response.statistics.*
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class ObsInfographicsRatingsTest {
    private fun player(id: Int = 1, passes: Int = 0, assists: Int = 0, goals: Int = 0,
                       blocks: Int = 0, marker: Int = 0, field: Int = 0, interceptions: Int = 0,
                       callahans: Int = 0, kind: MatchParticipantKind = MatchParticipantKind.PLAYER) = ParticipantStatisticsResponse(
        UUID(0, id.toLong()), kind, if (kind == MatchParticipantKind.UNKNOWN) 1 else null,
        "Имя", "Фамилия", "Имя Фамилия", 7,
        PlayerAttackStatisticsResponse(passes, 0, assists, goals, 0, 0, 0, 0, 0, 0, 0),
        DefenseStatisticsResponse(blocks, marker, field, interceptions, callahans), PlayerTimeStatisticsResponse(0, 0),
    )
    @Test fun `assist totals fifteen and Callahan rates defense only`() {
        val result = ObsInfographicsRatings.rate(player(passes = 3, assists = 1, goals = 2, callahans = 1))!!
        assertEquals(32L, result.attackTenths)
        assertEquals(75L, result.defenseTenths)
        assertEquals(2L, result.ordinaryAccuratePasses)
        assertEquals(1L, result.ratedGoals)
    }
    @Test fun `all legacy block counters contribute and Int max remains Long safe`() {
        assertEquals(65L, ObsInfographicsRatings.rate(player(blocks = 1, marker = 1, field = 1, interceptions = 1))!!.defenseTenths)
        assertEquals(3L * Int.MAX_VALUE * 15L, ObsInfographicsRatings.rate(player(blocks = Int.MAX_VALUE, marker = Int.MAX_VALUE, field = Int.MAX_VALUE))!!.defenseTenths)
    }
    @Test fun `inconsistent counters are unavailable instead of clamped`() {
        for (p in listOf(player(passes = -1), player(assists = 1), player(callahans = 1), player(blocks = -1)))
            assertNull(ObsInfographicsRatings.rate(p))
    }
    @Test fun `only positive PLAYER maxima compete and every tie uses canonical UUID order`() {
        val players = listOf(player(9, passes = 2), player(2, passes = 2), player(1, passes = 1),
            player(3, passes = 99, kind = MatchParticipantKind.UNKNOWN))
        val result = ObsInfographicsRatings.team(UUID(0, 50), "Команда", players)
        assertEquals(listOf(UUID(0, 2), UUID(0, 9)), result.attack.map { it.participantId })
        assertTrue(result.defense.isEmpty())
        assertEquals(ObsLeadersState.READY, result.state)
        assertTrue(ObsInfographicsRatings.team(UUID(0, 51), "Другая", listOf(player())).attack.isEmpty())
        assertEquals(ObsLeadersState.UNAVAILABLE, ObsInfographicsRatings.team(UUID(0, 51), "Другая", listOf(player(assists = 1))).state)
    }
}
