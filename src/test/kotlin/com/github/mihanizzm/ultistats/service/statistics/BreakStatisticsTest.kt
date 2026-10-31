package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest
class BreakStatisticsTest {
    @Autowired lateinit var aggregators: List<StatisticsAggregator>

    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val a = UUID.randomUUID()
    private val aReceiver = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val bReceiver = UUID.randomUUID()
    private val teams = mapOf(a to teamA, aReceiver to teamA, b to teamB, bReceiver to teamB)
    private val instant = Instant.parse("2026-01-01T00:00:00Z")
    private fun pull(player: UUID) = OnePlayerEvent(player, instant, EventType.PULL)
    private fun goal(from: UUID, to: UUID) = TwoPlayerEvent(from, to, instant, EventType.GOAL)
    private fun callahan(from: UUID, to: UUID) = TwoPlayerEvent(from, to, instant, EventType.CALLAHAN)

    @Test
    fun `breaks follow pulling and scoring teams within each point`() {
        val cases = listOf(
            Triple("empty", emptyList(), listOf(0, 0)),
            Triple("pulling goal", listOf(pull(a), goal(a, aReceiver)), listOf(1, 0)),
            Triple("receiving goal", listOf(pull(a), goal(b, bReceiver)), listOf(0, 0)),
            Triple("pulling callahan", listOf(pull(a), callahan(b, aReceiver)), listOf(1, 0)),
            Triple("receiving callahan", listOf(pull(a), callahan(a, bReceiver)), listOf(0, 0)),
            // Cross-team historical input distinguishes receiver attribution from thrower attribution.
            Triple("goal receiver", listOf(pull(a), goal(b, aReceiver)), listOf(1, 0)),
            Triple("no pull", listOf(goal(a, aReceiver)), listOf(0, 0)),
            Triple("successive points", listOf(pull(a), goal(a, aReceiver), pull(b), goal(b, bReceiver)), listOf(1, 1)),
            Triple("closed point", listOf(pull(a), goal(b, bReceiver), goal(a, aReceiver)), listOf(0, 0)),
            Triple("halftime reset", listOf(pull(a), SystemEvent(instant, EventType.HALFTIME_START),
                SystemEvent(instant, EventType.HALFTIME_END), goal(a, aReceiver)), listOf(0, 0)),
            Triple("new halftime pull", listOf(pull(a), SystemEvent(instant, EventType.HALFTIME_START),
                SystemEvent(instant, EventType.HALFTIME_END), pull(b), goal(b, bReceiver)), listOf(0, 1)),
            Triple("turnover alone", listOf(pull(a), TwoPlayerEvent(b, a, instant, EventType.INTERCEPTION)), listOf(0, 0)),
        )
        cases.forEach { (name, events, expected) ->
            val initial = MatchStatistics(emptyList(), listOf(TeamStatistics(teamA), TeamStatistics(teamB)))
            val actual = aggregators.fold(initial) { stats, aggregator -> aggregator.aggregate(stats, events, teams) }
            assertThat(actual.teamStatistics.map { it.attack.breaks })
                .describedAs(name).containsExactlyElementsOf(expected)
        }
    }
}
