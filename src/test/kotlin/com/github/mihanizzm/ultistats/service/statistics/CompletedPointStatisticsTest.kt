package com.github.mihanizzm.ultistats.service.statistics

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.dto.request.PointCompletionActionType
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.obs.ObsScoreboardService
import com.github.mihanizzm.ultistats.service.PointCompletionHistoryRejected
import com.github.mihanizzm.ultistats.service.PointCompletionHistoryService
import com.github.mihanizzm.ultistats.service.PointLineupClient
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletedPointStatisticsTest : MatchAbstractTest() {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var history: PointCompletionHistoryService
    @Autowired private lateinit var scoreboard: ObsScoreboardService

    private val matchId = UUID.randomUUID()
    private val a = PLAYERS_1.map { it.id }
    private val b = PLAYERS_2.map { it.id }
    private var sequence = 0

    @BeforeEach
    fun createFixture() {
        matchService.create(Match(matchId, MATCH.teamIds))
        matchService.startMatch(matchId, Instant.EPOCH)
    }

    @Test
    fun `approved timeline attributes actions and actual substitution while its marker owns score and breaks`() {
        event("PASS", 3, b[0], b[1])
        event("TIMEOUT_START", 4, team = TEAM_2.id)
        val timeoutEnd = event("TIMEOUT_END", 5, team = TEAM_2.id)
        event("PASS", 8, a[0], a[1])
        val reserveSlot = UUID.randomUUID()
        val temporaryPuller = UUID.randomUUID()
        val point = PointTimeline.from(matchId, MatchStatus.IN_PROGRESS, emptyList()).single().pointId
        jdbc.update("INSERT INTO match_point_lineups (match_id, point_id, revision) VALUES (?, ?, 1)", matchId, point)
        jdbc.update(
            """INSERT INTO match_point_lineup_entries
               (match_id, point_id, team_id, position, slot_id, slot_number, resolved_participant_id)
               VALUES (?, ?, ?, 0, ?, 9, ?)""",
            matchId, point, TEAM_1.id, reserveSlot, a[2],
        )
        jdbc.update(
            """INSERT INTO match_point_lineup_entries (match_id, point_id, team_id, position, slot_id, slot_number)
               VALUES (?, ?, ?, 1, ?, 10)""",
            matchId, point, TEAM_1.id, temporaryPuller,
        )
        val substitution = event("PLAYER_SUBSTITUTION", 9, a[1], toSlot = reserveSlot)
        jdbc.update("UPDATE match_point_lineup_entries SET introduced_by_event_id = ? WHERE slot_id = ?", substitution, reserveSlot)
        val marker = event("POINT_RESULT", 13, team = TEAM_1.id)
        event("PULL", 15, a[0])
        event("POINT_RESULT", 18, team = TEAM_2.id)
        finish(1, 1)

        val inserts = listOf(
            action("PULL", temporaryPuller, at = 0), action("PICKUP", b[0], at = 1000),
            action("INCOMPLETE_PASS", b[1], after = timeoutEnd),
            action("PICKUP", a[0], after = timeoutEnd),
            action("GOAL", reserveSlot, a[0], substitution, at = 11000),
        )
        val raw = stats()
        assertEquals(Duration.ofSeconds(6), raw.timeStatistics.totalTime)
        jdbc.update(
            """INSERT INTO match_point_completion_drafts
               (match_id, point_id, closing_event_id, revision, base_log_revision, insertions)
               VALUES (?, ?, ?, 1, 0, ?)""",
            matchId, point, marker, json.writeValueAsString(inserts),
        )
        assertEquals(raw, stats())
        approve(marker, inserts)

        val result = stats()
        val home = result.teamStatistics.first { it.teamId == TEAM_1.id }
        val away = result.teamStatistics.first { it.teamId == TEAM_2.id }
        val people = result.playerStatistics.associateBy { it.participantId }
        assertEquals(listOf(1, 1), result.teamStatistics.map { it.attack.score })
        assertEquals(listOf(1, 1), scoreboard.exactSnapshot(matchId).scoreboard.teams.map { it.score })
        assertEquals(1, home.attack.breaks)
        assertEquals(0, away.attack.breaks)
        assertEquals(2, home.attack.pulls)
        assertEquals(1, people.getValue(temporaryPuller).attack.pulls)
        assertEquals(1, people.getValue(a[0]).attack.pulls)
        assertEquals(2, home.attack.completePasses)
        assertEquals(1, away.attack.completePasses)
        assertEquals(2, away.attack.allPasses)
        assertEquals(1, people.getValue(a[0]).attack.goals)
        assertEquals(1, people.getValue(a[2]).attack.assists)
        assertEquals(1, people.getValue(b[1]).attack.incompletePasses)
        assertEquals(Duration.ofSeconds(2), people.getValue(a[2]).time.averagePossessionTime)
        assertTrue(people.getValue(a[2]).time.averagePossessionTimeAvailable)
        assertFalse(people.getValue(a[0]).time.averagePossessionTimeAvailable)
        assertEquals(Duration.ofSeconds(1), people.getValue(b[1]).time.totalPossessionTime)
        assertFalse(people.getValue(b[1]).time.averagePossessionTimeAvailable)
        assertEquals(Duration.ofSeconds(3), home.time.totalPossessionTime)
        assertEquals(Duration.ofSeconds(3), away.time.totalPossessionTime)
        assertEquals(Duration.ofSeconds(4), result.timeStatistics.timeSpentBetweenPoints)
        assertEquals(Duration.ofSeconds(1), result.timeStatistics.timeSpentOnTimeouts)
        assertEquals(Duration.ofSeconds(12), result.timeStatistics.totalTime)
        assertEquals(Duration.ofSeconds(7), result.timeStatistics.pureGameTime)
    }

    @Test
    fun `restored Callahan supplies defense and a goal without inventing a pull or a clock`() {
        val marker = event("POINT_RESULT", 40, team = TEAM_1.id)
        event("PULL", 50, a[0])
        event("POINT_RESULT", 60, team = TEAM_2.id)
        finish(1, 1)
        approve(marker, listOf(action("POSSESSION_SET", b[0]), action("CALLAHAN", b[0], a[0])))

        val result = stats()
        assertEquals(listOf(1, 1), result.teamStatistics.map { it.attack.score })
        assertEquals(listOf(0, 0), result.teamStatistics.map { it.attack.breaks })
        assertEquals(1, result.teamStatistics[0].defense.callahans)
        assertEquals(1, result.teamStatistics[1].attack.allPasses)
        assertEquals(1, result.playerStatistics.first { it.participantId == a[0] }.attack.goals)
        assertEquals(1, result.playerStatistics.first { it.participantId == b[0] }.attack.callahanDrops)
        assertEquals(Duration.ZERO, result.timeStatistics.totalTime)
    }

    @Test
    fun `an approved partial point ends observed play without using its registration clock`() {
        event("PASS", 10, a[0], a[1])
        val marker = event("POINT_RESULT", 30, team = TEAM_1.id)
        event("TIMEOUT_START", 40, team = TEAM_1.id)
        event("TIMEOUT_END", 44, team = TEAM_1.id)
        event("PULL", 50, a[0])
        event("POINT_RESULT", 60, team = TEAM_2.id)
        finish(1, 1)
        approve(marker, listOf(action("POSSESSION_SET", a[0], at = 0)))

        val result = stats()
        val possession = result.playerStatistics.associateBy { it.participantId }
        assertEquals(Duration.ofSeconds(10), possession.getValue(a[0]).time.averagePossessionTime)
        assertFalse(possession.getValue(a[1]).time.averagePossessionTimeAvailable)
        assertEquals(Duration.ofSeconds(4), result.timeStatistics.timeSpentOnTimeouts)
        assertEquals(Duration.ofSeconds(6), result.timeStatistics.timeSpentBetweenPoints)
        assertEquals(Duration.ofSeconds(20), result.timeStatistics.totalTime)
        assertEquals(Duration.ofSeconds(10), result.timeStatistics.pureGameTime)
    }

    @Test
    fun `a mismatched restored scoring team is rejected consistently by both readers`() {
        val marker = event("POINT_RESULT", 40, team = TEAM_1.id)
        finish(1, 0)
        approve(marker, listOf(action("GOAL", b[0], b[1])))
        val code = MatchProblemCode.POINT_COMPLETION_HISTORY_CONFLICT
        assertEquals(code, assertFailsWith<PointCompletionHistoryRejected> { stats() }.problem.code)
        assertEquals(code, assertFailsWith<PointCompletionHistoryRejected> {
            history.read(matchId, PointLineupClient.POINT_RESULTS)
        }.problem.code)
    }

    private fun stats() = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(matchId))

    private fun event(type: String, second: Long, from: UUID? = null, to: UUID? = null,
                      team: UUID? = null, toSlot: UUID? = null): UUID {
        val id = UUID.randomUUID()
        val clock = stamp(second)
        jdbc.update(
            """INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, original_occurred_at,
               from_participant_id, to_participant_id, to_slot_id, team_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            id, matchId, ++sequence, type, clock, clock.toInstant().toString(), from, to, toSlot, team,
        )
        return id
    }

    private fun finish(home: Int, away: Int) {
        jdbc.update("UPDATE matches SET ended_at = ? WHERE id = ?", stamp(100), matchId)
        jdbc.update("UPDATE match_teams SET score = ? WHERE match_id = ? AND team_id = ?", home, matchId, TEAM_1.id)
        jdbc.update("UPDATE match_teams SET score = ? WHERE match_id = ? AND team_id = ?", away, matchId, TEAM_2.id)
    }

    private fun approve(marker: UUID, actions: List<PointCompletionInsertion>) {
        val journal = eventService.getAllEventsOfMatch(matchId, PointLineupClient.POINT_RESULTS)
        val point = PointTimeline.from(matchId, MatchStatus.FINISHED, journal).single { it.closingEventId == marker }
        jdbc.update(
            "INSERT INTO match_point_completion_approvals (match_id, point_id, closing_event_id, insertions) VALUES (?, ?, ?, ?)",
            matchId, point.pointId, marker, json.writeValueAsString(actions),
        )
    }

    private fun action(type: String, from: UUID, to: UUID? = null, after: UUID? = null, at: Long? = null) =
        PointCompletionInsertion(UUID.randomUUID(), after, PointCompletionActionType.valueOf(type), from, to, at)

    private fun stamp(second: Long) = OffsetDateTime.ofInstant(Instant.ofEpochSecond(second), ZoneOffset.UTC)
}
