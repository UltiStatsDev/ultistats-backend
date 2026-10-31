package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.model.statistics.PlayerTimeStatistics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AveragePossessionTimeTest : MatchAbstractTest() {
    private fun ts(seconds: Long) = Instant.parse(START_DATE).plusSeconds(seconds)
    private fun append(event: Event) {
        assertThat(eventService.create(event, MATCH.id)).isInstanceOf(EventCommandResult.Success::class.java)
    }
    private fun one(type: EventType, player: Int, seconds: Long) =
        append(OnePlayerEvent(UUIDS[player], ts(seconds), type))
    private fun two(type: EventType, from: Int, to: Int, seconds: Long) =
        append(TwoPlayerEvent(UUIDS[from], UUIDS[to], ts(seconds), type))
    private fun time(player: Int): PlayerTimeStatistics = statisticsService
        .recalculateMatchStatistics(matchService.getOrThrow(MATCH.id))
        .playerStatistics.first { it.participantId == UUIDS[player] }.time

    @Test
    fun `completed four six two second holds average four seconds without changing pickup count`() {
        one(EventType.PULL, 2, 0)
        one(EventType.PICKUP, 7, 1)
        two(EventType.PASS, 7, 8, 5)
        two(EventType.PASS, 8, 7, 6)
        two(EventType.PASS, 7, 8, 12)
        two(EventType.PASS, 8, 7, 13)
        two(EventType.GOAL, 7, 8, 15)
        val statistics = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(MATCH.id))
        val player = statistics.playerStatistics.first { it.participantId == UUIDS[7] }
        assertThat(player.time.averagePossessionTime).isEqualTo(Duration.ofMillis(4000))
        assertThat(player.time.totalPossessionTime).isEqualTo(Duration.ofSeconds(12))
        assertThat(player.attack.discPossessions).isEqualTo(1)
        assertThat(time(8).averagePossessionTime).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `timeout pauses a single episode and open suffix contributes neither duration nor count`() {
        one(EventType.PULL, 2, 0)
        one(EventType.PICKUP, 7, 1)
        append(TeamEvent(UUIDS[1], ts(3), EventType.TIMEOUT_START))
        append(TeamEvent(UUIDS[1], ts(63), EventType.TIMEOUT_END))
        two(EventType.PASS, 7, 8, 65)
        two(EventType.PASS, 8, 7, 66)
        append(TeamEvent(UUIDS[1], ts(76), EventType.TIMEOUT_START))
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(4))
        assertThat(time(7).totalPossessionTime).isEqualTo(Duration.ofSeconds(14))
    }

    @Test
    fun `interception starts a hold and callahan only completes the thrower hold`() {
        one(EventType.PULL, 2, 0)
        one(EventType.PICKUP, 7, 1)
        two(EventType.INTERCEPTION, 7, 2, 4)
        two(EventType.CALLAHAN, 2, 8, 9)
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(3))
        assertThat(time(2).averagePossessionTime).isEqualTo(Duration.ofSeconds(5))
        assertThat(time(8).averagePossessionTimeAvailable).isFalse()
    }

    @Test
    fun `block and incomplete pass complete holds while loose disc time is excluded`() {
        one(EventType.PULL, 2, 0)
        one(EventType.PICKUP, 7, 1)
        two(EventType.BLOCK_FIELD, 7, 2, 5)
        one(EventType.PICKUP, 2, 20)
        one(EventType.INCOMPLETE_PASS, 2, 26)
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(4))
        assertThat(time(2).averagePossessionTime).isEqualTo(Duration.ofSeconds(6))
    }

    @Test
    fun `append undo and replay rebuild agree on completed episodes`() {
        one(EventType.PULL, 2, 0)
        one(EventType.PICKUP, 7, 1)
        two(EventType.PASS, 7, 8, 5)
        two(EventType.PASS, 8, 7, 6)
        two(EventType.GOAL, 7, 8, 12)
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(5))
        eventService.undoLast(MATCH.id)
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(4))
        val events = matchService.getOrThrow(MATCH.id).events.toList()
        val seed = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(MATCH.id))
        val rebuilt = TotalTimeStatisticsAggregator().aggregate(seed, events,
            matchService.getOrThrow(MATCH.id).participantsByTeam.flatMap { (team, participants) ->
                participants.map { it.participantId to team }
            }.toMap())
        assertThat(rebuilt.playerStatistics.first { it.participantId == UUIDS[7] }.time)
            .isEqualTo(time(7))
        two(EventType.GOAL, 7, 8, 12)
        assertThat(time(7).averagePossessionTime).isEqualTo(Duration.ofSeconds(5))
    }
}
