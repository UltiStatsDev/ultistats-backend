package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PointResultReplayTest {
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val a = UUID.randomUUID()
    private val b1 = UUID.randomUUID()
    private val b2 = UUID.randomUUID()
    private val actorTeams = mapOf(a to teamA, b1 to teamB, b2 to teamB)

    @Test
    fun `each result keeps the stable id of its next point and belongs to the point it closes`() {
        val matchId = UUID.randomUUID()
        val journal = listOf(
            one(a, 0, EventType.PULL), one(b1, 1, EventType.PICKUP),
            two(b1, b2, 3, EventType.GOAL),
            result(9),
            one(a, 20, EventType.PULL), one(b1, 22, EventType.PICKUP),
            two(b1, a, 25, EventType.CALLAHAN),
        ).mapIndexed { index, event -> StoredEvent(UUID.randomUUID(), index + 1, event) }
        fun pointId(openingId: UUID?) =
            UUID.nameUUIDFromBytes("ultistats:point:v1:$matchId:${openingId ?: "first"}".encodeToByteArray())
        val legacy = PointTimeline.from(matchId, MatchStatus.IN_PROGRESS, journal.take(3))
        val projection = PointTimeline.from(matchId, MatchStatus.IN_PROGRESS, journal)
        assertEquals(listOf(pointId(null), pointId(journal[2].id), pointId(journal[3].id), pointId(journal[6].id)),
            projection.map { it.pointId })
        assertEquals(legacy[0], projection[0])
        assertEquals(legacy[1].pointId, projection[1].pointId)
        assertEquals(listOf(PointStatus.COMPLETED, PointStatus.COMPLETED, PointStatus.COMPLETED, PointStatus.NEXT),
            projection.map { it.status })
        assertEquals(listOf(journal[2].id, journal[3].id, journal[6].id, null), projection.map { it.closingEventId })
        assertEquals(listOf(journal[0].id, null, journal[4].id, null), projection.map { it.pullEventId })
        assertEquals(listOf(journal.take(3), journal.subList(3, 4), journal.drop(4)),
            PointTimeline.eventsByPoint(projection, journal).values.toList())
        assertEquals(projection.dropLast(1), PointTimeline.from(matchId, MatchStatus.FINISHED, journal))
    }

    @Test
    fun `unmeasured gaps preserve explicit pause lengths and only completed holds affect the average`() {
        val journal = listOf(
            one(a, 5, EventType.PULL), one(b1, 6, EventType.PICKUP), two(b1, b2, 10, EventType.PASS),
            result(20),
            TeamEvent(teamA, at(30), EventType.TIMEOUT_START), TeamEvent(teamA, at(33), EventType.TIMEOUT_END),
            SystemEvent(at(35), EventType.HALFTIME_START), SystemEvent(at(40), EventType.HALFTIME_END),
            one(a, 50, EventType.PULL), one(b1, 52, EventType.PICKUP), two(b1, a, 55, EventType.CALLAHAN),
        )
        val announcements = listOf(
            MatchAnnouncement(UUID.randomUUID(), null, at(12), at(18)),
            MatchAnnouncement(UUID.randomUUID(), teamB, at(31), at(37)),
            MatchAnnouncement(UUID.randomUUID(), null, at(53), at(54)),
        )
        val empty = MatchStatistics(actorTeams.keys.map { PlayerStatistics(it) }, listOf(TeamStatistics(teamA), TeamStatistics(teamB)))
        val stats = TotalTimeStatisticsAggregator().aggregate(empty, journal, actorTeams, announcements)
        val time = stats.timeStatistics
        assertEquals(Duration.ofSeconds(18), time.totalTime)
        assertEquals(Duration.ofSeconds(9), time.pureGameTime)
        assertEquals(Duration.ZERO, time.timeSpentBetweenPoints)
        assertEquals(Duration.ofSeconds(3), time.timeSpentOnTimeouts)
        assertEquals(Duration.ofSeconds(5), time.timeSpentOnHalftime)
        assertEquals(Duration.ofSeconds(6), stats.teamStatistics.single { it.teamId == teamB }.time.totalPossessionTime)
        val thrower = stats.playerStatistics.single { it.participantId == b1 }.time
        assertEquals(Duration.ofSeconds(6), thrower.totalPossessionTime)
        assertEquals(Duration.ofSeconds(3), thrower.averagePossessionTime)
        val halfRecordedHold = stats.playerStatistics.single { it.participantId == b2 }.time
        assertEquals(Duration.ZERO, halfRecordedHold.totalPossessionTime)
        assertFalse(halfRecordedHold.averagePossessionTimeAvailable)
    }

    private fun at(seconds: Long) = Instant.EPOCH.plusSeconds(seconds)
    private fun one(actor: UUID, seconds: Long, type: EventType) = OnePlayerEvent(actor, at(seconds), type)
    private fun two(from: UUID, to: UUID, seconds: Long, type: EventType) = TwoPlayerEvent(from, to, at(seconds), type)
    private fun result(seconds: Long) = TeamEvent(teamA, at(seconds), EventType.POINT_RESULT)
}
