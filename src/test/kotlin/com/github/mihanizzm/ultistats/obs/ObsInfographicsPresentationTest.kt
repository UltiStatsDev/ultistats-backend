package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ObsInfographicsPresentationTest : MatchAbstractTest() {
    @Autowired private lateinit var commands: ObsInfographicsPresentationService
    @Autowired private lateinit var preview: ObsPreviewService
    @Autowired private lateinit var settings: ObsInfographicsSettingsService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var tx: TransactionTemplate

    @BeforeEach fun resetCommands() {
        jdbc.update("DELETE FROM obs_infographics_presentations")
    }

    private fun goal() {
        val start = Instant.parse(START_DATE).plusSeconds(10)
        for (event in listOf(
            OnePlayerEvent(PLAYERS_2[0].id, start, EventType.PULL),
            OnePlayerEvent(PLAYERS_1[0].id, start.plusSeconds(1), EventType.PICKUP),
            TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, start.plusSeconds(2), EventType.GOAL),
        )) assertIs<EventCommandResult.Success>(eventService.create(event, MATCH.id))
    }

    private fun request(binding: ObsPresentationBinding = ObsPresentationBinding.EXACT): ObsPresentationRequest {
        val snapshot = if (binding == ObsPresentationBinding.EXACT) commands.exactLeaders(MATCH.id) else commands.autoLeaders().snapshot!!
        val control = snapshot.presentation!!
        return ObsPresentationRequest(binding, snapshot.matchId, control.expectedInputRevision,
            control.expectedSettingsRevision, control.expectedGeneration, UUID.randomUUID())
    }

    @Test fun `committed finish publishes server-timed stats independently to exact and selected auto`() {
        goal()
        tx.executeWithoutResult { transaction ->
            assertIs<MatchCommandResult.Success<*>>(matchService.endMatch(MATCH.id, Instant.parse(END_DATE)))
            transaction.setRollbackOnly()
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM obs_infographics_presentations", Int::class.java))

        val before = Instant.now().minusSeconds(1)
        assertIs<MatchCommandResult.Success<*>>(matchService.endMatch(MATCH.id, Instant.parse(END_DATE)))
        val exact = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(preview.infographics(MATCH.id))
        val auto = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(preview.autoInfographics().snapshot)
        assertEquals("HIDDEN", exact.path("phase").path("kind").asText())
        for (snapshot in listOf(exact, auto)) {
            val active = snapshot.path("statsPresentation")
            assertEquals("STATS", active.path("kind").asText())
            assertEquals("AUTO", active.path("origin").asText())
            val start = Instant.parse(active.path("startedAt").asText())
            assertTrue(start >= before)
            assertEquals(start.plusSeconds(active.path("pageSeconds").asLong()), Instant.parse(active.path("expiresAt").asText()))
        }
        assertNotEquals(exact.path("statsPresentation").path("generation"), auto.path("statsPresentation").path("generation"))
    }

    @Test fun `only newest durable command is effective and retry is the original receipt`() {
        val noPlayers = assertFailsWith<AuthProblem> { commands.show(request()) }
        assertEquals("OBS_PRESENTATION_NO_LEADERS", noPlayers.code)
        goal()
        val first = request()
        val started = commands.show(first)
        assertEquals(started, commands.show(first))
        assertEquals(started.startedAt.plusSeconds(started.pageSeconds * 2L), started.expiresAt)
        assertEquals(listOf(2, 0), preview.infographics(MATCH.id).leaderPresentation!!.teams.map { it.attack.size })
        val pending = request()
        val newest = commands.show(pending)
        assertEquals("OBS_PRESENTATION_GENERATION_STALE",
            assertFailsWith<AuthProblem> { commands.show(pending.copy(operationId = UUID.randomUUID())) }.code)
        assertEquals("OBS_PRESENTATION_GENERATION_STALE", assertFailsWith<AuthProblem> { commands.hide(started.generation) }.code)
        assertEquals(newest.generation, preview.infographics(MATCH.id).leaderPresentation?.generation)
        commands.hide(newest.generation)
        commands.hide(newest.generation)
        assertEquals(started, commands.show(first))
        assertEquals(newest, commands.show(pending))
        assertNull(preview.infographics(MATCH.id).leaderPresentation)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM obs_infographics_presentations", Int::class.java))
    }

    @Test fun `settings and undo invalidate active and pending commands`() {
        goal()
        val started = commands.show(request())
        val pending = request()
        val current = settings.get()
        settings.save(json.valueToTree(current.copy(leaderPageSeconds = if (current.leaderPageSeconds == 7) 8 else 7)))
        assertNull(preview.infographics(MATCH.id).leaderPresentation)
        assertEquals("OBS_PRESENTATION_INPUT_STALE", assertFailsWith<AuthProblem> { commands.show(pending) }.code)
        val fresh = commands.show(request())
        assertNotEquals(started.pageSeconds, fresh.pageSeconds)
        val beforeUndo = request()
        assertIs<EventCommandResult.Success>(eventService.undoLast(MATCH.id))
        assertNull(preview.infographics(MATCH.id).leaderPresentation)
        assertEquals("OBS_PRESENTATION_INPUT_STALE", assertFailsWith<AuthProblem> { commands.show(beforeUndo) }.code)
    }

    @Test fun `AUTO switch and soft deletion cannot revive a prior presentation`() {
        goal()
        val operation = request(ObsPresentationBinding.AUTO)
        val started = commands.show(operation)
        val pending = request(ObsPresentationBinding.AUTO)
        val other = UUID.randomUUID()
        matchService.create(Match(other, listOf(TEAM_1.id, TEAM_2.id)))
        matchService.startMatch(other, Instant.parse(START_DATE).plusSeconds(20))
        assertEquals(other, preview.autoInfographics().snapshot?.matchId)
        assertNull(preview.autoInfographics().snapshot?.leaderPresentation)
        matchService.delete(other)
        val restored = preview.autoInfographics().snapshot!!
        assertEquals(MATCH.id, restored.matchId)
        assertNull(restored.leaderPresentation)
        assertEquals("OBS_PRESENTATION_INPUT_STALE", assertFailsWith<AuthProblem> { commands.show(pending) }.code)
        assertEquals(started, commands.show(operation))
    }
}
