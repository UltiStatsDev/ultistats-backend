package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@AutoConfigureMockMvc
class UndoLastEventControllerTest : MatchAbstractTest() {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var eventRepository: SpringDataEventRepository

    private lateinit var undoMatch: Match

    @BeforeEach
    fun createUniqueMatch() {
        undoMatch = Match(UUID.randomUUID(), listOf(TEAM_1.id, TEAM_2.id))
        matchService.create(undoMatch)
        assertIs<MatchCommandResult.Success<Match>>(matchService.startMatch(undoMatch.id, EVENT_AT))
    }

    @Test
    fun `undo returns and soft deletes the last event even with equal timestamps`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        val pickup = record(OnePlayerEvent(PLAYERS_1[0].id, EVENT_AT, EventType.PICKUP))

        mockMvc.perform(post(undoPath()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(pickup.id.toString()))
            .andExpect(jsonPath("$.sequenceNumber").value(2))
            .andExpect(jsonPath("$.type").value("PICKUP"))
            .andExpect(jsonPath("$.participantId").value(PLAYERS_1[0].id.toString()))

        assertEquals(listOf(pull), eventService.getAllEventsOfMatch(undoMatch.id))
        assertNotNull(eventRepository.findById(pickup.id).orElseThrow().deletedAt)
        mockMvc.perform(get("/api/v1/matches/${undoMatch.id}/events/${pickup.id}"))
            .andExpect(status().isNotFound)

        mockMvc.perform(post(undoPath()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(pull.id.toString()))
        assertTrue(eventService.getAllEventsOfMatch(undoMatch.id).isEmpty())

        val nextPull = record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        assertEquals(3, nextPull.sequenceNumber)
    }

    @Test
    fun `undo skips an already deleted tail and does not affect another match`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        val pickup = record(OnePlayerEvent(PLAYERS_1[0].id, EVENT_AT, EventType.PICKUP))
        assertIs<EventCommandResult.Deleted>(eventService.remove(pickup.id, undoMatch.id))
        val other = Match(UUID.randomUUID(), listOf(TEAM_1.id, TEAM_2.id))
        matchService.create(other)
        assertIs<MatchCommandResult.Success<Match>>(matchService.startMatch(other.id, EVENT_AT))
        val otherPull = assertIs<EventCommandResult.Success>(
            eventService.create(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL), other.id),
        ).event

        mockMvc.perform(post(undoPath()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(pull.id.toString()))

        assertEquals(listOf(otherPull), eventService.getAllEventsOfMatch(other.id))
        mockMvc.perform(post(undoPath()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `undoing a goal recalculates score and permits a replacement goal`() {
        recordCompletedPoint()
        assertEquals(1, matchService.getOrThrow(undoMatch.id).teamScores.single { it.teamId == TEAM_1.id }.score)

        mockMvc.perform(post(undoPath()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("GOAL"))

        val updated = matchService.getOrThrow(undoMatch.id)
        assertEquals(0, updated.teamScores.single { it.teamId == TEAM_1.id }.score)
        assertEquals(2, updated.eventCount)
        val stats = statisticsService.recalculateMatchStatistics(updated)
        assertEquals(0, stats.playerStatistics.single { it.participantId == PLAYERS_1[1].id }.attack.goals)
        assertEquals(0, stats.playerStatistics.single { it.participantId == PLAYERS_1[0].id }.attack.assists)
        val replacement = record(TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, EVENT_AT, EventType.GOAL))
        assertEquals(4, replacement.sequenceNumber)
        assertEquals(1, matchService.getOrThrow(undoMatch.id).teamScores.single { it.teamId == TEAM_1.id }.score)
    }

    @Test
    fun `empty event log returns problem detail without pretending the match is missing`() {
        mockMvc.perform(post(undoPath()))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value(undoPath()))
        assertNotNull(matchService.get(undoMatch.id))
    }

    @Test
    fun `unknown or deleted match returns not found`() {
        mockMvc.perform(post(undoPath(UUID.randomUUID())))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        matchService.delete(undoMatch.id)
        mockMvc.perform(post(undoPath()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `undo preserves completed point invariant in a finished match`() {
        val goal = recordCompletedPoint()
        assertIs<MatchCommandResult.Success<Match>>(matchService.endMatch(undoMatch.id, EVENT_AT.plusSeconds(1)))

        mockMvc.perform(post(undoPath()))
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("EVENT_SEQUENCE_VIOLATION"))

        assertEquals(goal, eventService.get(goal.id, undoMatch.id))
        assertNull(eventRepository.findById(goal.id).orElseThrow().deletedAt)
        assertEquals(1, matchService.getOrThrow(undoMatch.id).teamScores.single { it.teamId == TEAM_1.id }.score)
    }

    @Test
    fun `concurrent undo requests remove successive events without losing an undo`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        val pickup = record(OnePlayerEvent(PLAYERS_1[0].id, EVENT_AT, EventType.PICKUP))
        val executor = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                executor.submit<String> {
                    check(gate.await(5, TimeUnit.SECONDS))
                    val response = mockMvc.perform(post(undoPath()))
                        .andExpect(status().isOk)
                        .andReturn().response
                    objectMapper.readTree(response.contentAsString)["id"].asText()
                }
            }
            gate.countDown()
            val ids = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(setOf(pull.id.toString(), pickup.id.toString()), ids.toSet())
            assertTrue(eventService.getAllEventsOfMatch(undoMatch.id).isEmpty())
        } finally {
            gate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `OpenAPI documents undo without a request body and with typed responses`() {
        val path = "$.paths['/api/v1/matches/{matchId}/events/undo'].post"
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$path.requestBody").doesNotExist())
            .andExpect(jsonPath("$path.responses['200'].content['application/json'].schema['\$ref']")
                .value("#/components/schemas/EventResponse"))
            .andExpect(jsonPath("$path.responses['404'].content['application/problem+json'].schema['\$ref']")
                .value("#/components/schemas/ProblemDetail"))
            .andExpect(jsonPath("$path.responses['409'].content['application/problem+json'].schema['\$ref']")
                .value("#/components/schemas/ProblemDetail"))
    }

    private fun record(event: Event): StoredEvent =
        assertIs<EventCommandResult.Success>(eventService.create(event, undoMatch.id)).event

    private fun recordCompletedPoint(): StoredEvent {
        record(OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL))
        record(OnePlayerEvent(PLAYERS_1[0].id, EVENT_AT, EventType.PICKUP))
        return record(TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, EVENT_AT, EventType.GOAL))
    }

    private fun undoPath(matchId: UUID = undoMatch.id) = "/api/v1/matches/$matchId/events/undo"

    private companion object {
        val EVENT_AT: Instant = Instant.parse("2026-10-24T10:00:00Z")
    }
}
