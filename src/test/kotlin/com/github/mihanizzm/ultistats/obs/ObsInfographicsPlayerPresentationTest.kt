package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupPlayer
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupRequest
import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupTeam
import com.github.mihanizzm.ultistats.dto.request.OnePlayerEventRequest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.service.PointLineupClient
import com.github.mihanizzm.ultistats.service.PointLineupService
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@TestPropertySource(properties = ["ultistats.point-lineups.write-enabled=true"])
class ObsInfographicsPlayerPresentationTest : MatchAbstractTest() {
    @Autowired private lateinit var commands: ObsInfographicsPresentationService
    @Autowired private lateinit var preview: ObsPreviewService
    @Autowired private lateinit var lineups: PointLineupService
    @Autowired private lateinit var settings: ObsInfographicsSettingsService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    private val matchId = UUID.randomUUID()

    @BeforeEach fun createIndependentMatch() {
        matchService.delete(MATCH.id)
        matchService.create(Match(matchId, listOf(TEAM_1.id, TEAM_2.id)))
        assertIs<MatchCommandResult.Success<*>>(matchService.startMatch(matchId, Instant.parse(START_DATE)))
    }

    private fun exact() = commands.exactLeaders(matchId).presentation!!
    private fun automatic() = commands.autoLeaders().snapshot!!.presentation!!

    private fun ObsPresentationControl.show(kind: ObsPresentationKind) =
        ObsPresentationRequest(binding, matchId, expectedInputRevision, expectedSettingsRevision,
            expectedGeneration, UUID.randomUUID(), kind)

    private fun ObsPresentationControl.hide() =
        ObsPhaseHideRequest(binding, matchId, expectedGeneration, assertNotNull(activePhase).phaseToken, UUID.randomUUID())

    private fun confirm() {
        val timeline = lineups.read(matchId)!!
        val next = timeline.points.single { it.status == PointStatus.NEXT }
        val teams = listOf(TEAM_1.id to PLAYERS_1, TEAM_2.id to PLAYERS_2)
        assertNotNull(lineups.confirm(matchId, next.pointId, ConfirmPointLineupRequest(timeline.logRevision,
            teams.map { (id, players) -> ConfirmPointLineupTeam(id, players.map { ConfirmPointLineupPlayer(it.id) }) })))
    }

    @Test fun `manual roster and confirmed NEXT share the finite sequence and capture policy`() {
        val initial = exact()
        assertEquals(ObsPresentationBinding.EXACT, initial.binding)
        assertEquals(matchId, initial.matchId)
        assertEquals(mapOf("LEADERS" to false, "STATS" to false, "ROSTER" to true, "POINT" to false),
            initial.availability.mapKeys { it.key.name })
        assertEquals("OBS_PRESENTATION_NO_POINT",
            assertFailsWith<AuthProblem> { commands.show(initial.show(ObsPresentationKind.POINT)) }.code)
        val roster = commands.show(initial.show(ObsPresentationKind.ROSTER))
        assertEquals(roster.startedAt.plusSeconds(settings.get().rosterPageSeconds.toLong()), roster.expiresAt)
        assertEquals(roster, exact().activePresentation)
        assertEquals(roster, preview.infographics(matchId).playerPresentation)
        assertNull(automatic().activePresentation)

        confirm()
        assertNull(preview.infographics(matchId).playerPresentation)
        assertTrue(exact().availability.getValue(ObsPresentationKind.POINT))
        val oldPolicy = settings.get()
        val command = exact().show(ObsPresentationKind.POINT)
        val point = commands.show(command)
        settings.save(json.valueToTree(oldPolicy.copy(pointPageSeconds = if (oldPolicy.pointPageSeconds == 7) 8 else 7)))
        assertEquals(point, commands.show(command))
        assertEquals(point, preview.infographics(matchId).playerPresentation)
        assertEquals(point.startedAt.plusSeconds(oldPolicy.pointPageSeconds.toLong()), point.expiresAt)
        assertEquals("OBS_PRESENTATION_GENERATION_STALE", assertFailsWith<AuthProblem> { commands.hide(roster.generation) }.code)

        val start = Instant.parse(START_DATE)
        jdbc.update("UPDATE obs_infographics_presentations SET started_at=?, expires_at=? WHERE generation=?",
            Timestamp.from(start), Timestamp.from(start.plusSeconds(point.pageSeconds.toLong())), point.generation)
        assertNull(exact().activePresentation)
        assertEquals(ObsPresentationKind.POINT, exact().activePhase?.kind)
        assertNull(preview.infographics(matchId).playerPresentation)
        assertEquals("LINEUP", preview.infographics(matchId).phase.kind)
    }

    @Test fun `base hide expires with its canonical phase and a replay cannot hide a newer show`() {
        val initial = exact()
        val captured = initial.hide()
        assertEquals(ObsPresentationKind.ROSTER, initial.activePhase?.kind)
        commands.hidePhase(captured)
        assertNull(exact().activePhase)
        assertNotNull(exact().expectedGeneration)
        assertEquals("ROSTER", preview.infographics(matchId).suppressedPhase)
        assertNull(preview.autoInfographics().snapshot!!.suppressedPhase)

        val currentShow = commands.show(exact().show(ObsPresentationKind.ROSTER))
        commands.hidePhase(captured)
        assertEquals(currentShow, exact().activePresentation)
        commands.hide(currentShow.generation)
        assertEquals(ObsPresentationKind.ROSTER, exact().activePhase?.kind)
        val stale = exact().hide()
        confirm()
        assertEquals("OBS_PRESENTATION_PHASE_STALE", assertFailsWith<AuthProblem> { commands.hidePhase(stale) }.code)

        val pointHide = exact().hide()
        commands.hidePhase(pointHide)
        assertEquals("LINEUP", preview.infographics(matchId).suppressedPhase)
        assertIs<EventCommandResult.Success>(eventService.create(matchId,
            OnePlayerEventRequest(EventType.PULL, Instant.parse(START_DATE).plusSeconds(10), PLAYERS_2[0].id),
            PointLineupClient.LINEUPS))
        assertNull(preview.infographics(matchId).suppressedPhase)
        assertFalse(exact().availability.getValue(ObsPresentationKind.POINT))
    }

    @Test fun `AUTO phase token cannot be moved or reused after displacement`() {
        val stale = automatic().hide()
        assertEquals("OBS_PRESENTATION_PHASE_STALE", assertFailsWith<AuthProblem> {
            commands.hidePhase(stale.copy(binding = ObsPresentationBinding.EXACT))
        }.code)
        val other = UUID.randomUUID()
        matchService.create(Match(other, listOf(TEAM_1.id, TEAM_2.id)))
        assertIs<MatchCommandResult.Success<*>>(matchService.startMatch(other, Instant.parse(START_DATE).plusSeconds(20)))
        assertEquals(other, automatic().matchId)
        assertEquals("OBS_PRESENTATION_INPUT_STALE", assertFailsWith<AuthProblem> { commands.hidePhase(stale) }.code)
        matchService.delete(other)
        assertEquals(matchId, automatic().matchId)
        assertEquals("OBS_PRESENTATION_PHASE_STALE", assertFailsWith<AuthProblem> { commands.hidePhase(stale) }.code)
        commands.hidePhase(automatic().hide())
        assertEquals("ROSTER", preview.autoInfographics().snapshot!!.suppressedPhase)
        assertNull(preview.infographics(matchId).suppressedPhase)
    }
}
