package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.fixture.MatchEventTestFixture
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.realtime.MatchEventStreamService
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertIs

@SpringBootTest
@AutoConfigureMockMvc
@Suppress("NonAsciiCharacters")
class MatchEventStreamControllerTest {
    private val matchEventFixture by lazy { MatchEventTestFixture(matchService, eventService) }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var matchService: MatchService

    @Autowired
    lateinit var eventService: EventService

    @Autowired
    lateinit var teamService: TeamService

    @Autowired
    lateinit var playerService: PlayerService

    @Autowired
    lateinit var teamPlayerService: TeamPlayerService

    @Autowired
    lateinit var streamService: MatchEventStreamService

    @AfterEach
    fun cleanUp() {
        matchService.getAll().forEach { match ->
            streamService.finishMatch(match.id)
            matchService.delete(match.id)
        }
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
    }

    @Test
    fun `SSE поток существующего матча возвращает connected событие с retry`() {
        val match = createMatch()

        val result = mockMvc.perform(
            get("/api/v1/matches/${match.id}/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM),
        )
            .andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andReturn()

        val body = awaitSseContent(result.response, match.id)
        assertThat(body.indexOf("event:connected")).isLessThan(body.indexOf("data:"))
        assertThat(body.indexOf("data:")).isLessThan(body.indexOf("retry:2000"))
        assertThat(connectedMatchId(body)).isEqualTo(match.id.toString())
    }

    @Test
    fun `SSE поток отсутствующего матча возвращает структурированный 404`() {
        val missingId = UUID.randomUUID()
        val instance = "/api/v1/matches/$missingId/events/stream"

        mockMvc.perform(get(instance).accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Match $missingId not found"))
            .andExpect(jsonPath("$.instance").value(instance))
    }

    @Test
    fun `SSE поток завершенного матча отправляет connected затем match-finished и завершается`() {
        val match = createMatch()
        assertIs<MatchCommandResult.Success<Match>>(matchService.startMatch(match.id, MATCH_STARTED_AT))
        matchEventFixture.recordCompletedPoint(match.id, MATCH_ENDED_AT.minusSeconds(1))
        assertIs<MatchCommandResult.Success<Match>>(matchService.endMatch(match.id, MATCH_ENDED_AT))

        val result = mockMvc.perform(
            get("/api/v1/matches/${match.id}/events/stream")
                .accept(MediaType.TEXT_EVENT_STREAM),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn()

        val body = awaitFinishedSseContent(result.response, match.id)
        val connectedEvent = "event:connected"
        val finishedEvent = "event:match-finished"
        assertThat(body).contains(connectedEvent, finishedEvent)
        assertThat(body.indexOf(connectedEvent)).isLessThan(body.indexOf(finishedEvent))
        mockMvc.perform(asyncDispatch(result))
            .andExpect(request().asyncNotStarted())
    }

    @Test
    fun `OpenAPI документирует SSE поток и ProblemDetail`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.paths['/api/v1/matches/{matchId}/events/stream'].get.responses['200'].content['text/event-stream'].schema.type")
                    .value("string"),
            )
            .andExpect(
                jsonPath("$.paths['/api/v1/matches/{matchId}/events/stream'].get.responses['404'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
    }

    private fun createMatch(): Match {
        val teams = listOf("Команда 1", "Команда 2").map(::createTeam)
        return Match(UUID.randomUUID(), teams.map(Team::id)).also(matchService::create)
    }

    private fun createTeam(name: String): Team {
        val team = Team(UUID.randomUUID(), name)
        val player = Player(UUID.randomUUID(), "Игрок", name)
        teamService.create(team)
        playerService.create(player)
        teamPlayerService.add(team.id, player.id, 1)
        return team
    }

    private fun awaitSseContent(response: MockHttpServletResponse, matchId: UUID): String {
        val connected = "event:connected"
        val retry = "retry:2000"
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        do {
            val content = response.contentAsString
            if (content.contains(connected) && content.contains(retry) && content.contains(matchId.toString())) {
                return content
            }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)

        return response.contentAsString
    }

    private fun awaitFinishedSseContent(response: MockHttpServletResponse, matchId: UUID): String {
        val connected = "event:connected"
        val finished = "event:match-finished"
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        do {
            val content = response.contentAsString
            if (content.contains(connected) && content.contains(finished) && content.contains(matchId.toString())) {
                return content
            }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)

        return response.contentAsString
    }

    private fun connectedMatchId(content: String): String = objectMapper.readTree(
        content.lineSequence()
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:") },
    ).path("matchId").asText()

    companion object {
        private val MATCH_STARTED_AT = Instant.parse("2026-09-02T10:00:00Z")
        private val MATCH_ENDED_AT = MATCH_STARTED_AT.plusSeconds(60)
    }
}
