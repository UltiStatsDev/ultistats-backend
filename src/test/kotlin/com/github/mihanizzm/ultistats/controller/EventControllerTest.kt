package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.statistics.StatisticsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(EventControllerTestConfiguration::class)
class EventControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var matchService: MatchService
    @Autowired lateinit var teamService: TeamService
    @Autowired lateinit var playerService: PlayerService
    @Autowired lateinit var teamPlayerService: TeamPlayerService
    @Autowired lateinit var statisticsService: StatisticsService
    @Autowired lateinit var eventFixture: EventControllerTestFixture
    @MockitoSpyBean lateinit var eventService: EventService

    private lateinit var match: Match
    private lateinit var team1: Team
    private lateinit var team2: Team
    private lateinit var player1: Player
    private lateinit var player2: Player
    private lateinit var opponent: Player

    @BeforeEach
    fun setUp() {
        matchService.getAll().forEach { matchService.delete(it.id) }
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
        team1 = Team(UUID.randomUUID(), "One")
        team2 = Team(UUID.randomUUID(), "Two")
        teamService.create(team1); teamService.create(team2)
        player1 = Player(UUID.randomUUID(), "First", "Player")
        player2 = Player(UUID.randomUUID(), "Second", "Player")
        opponent = Player(UUID.randomUUID(), "Other", "Player")
        listOf(player1, player2, opponent).forEach(playerService::create)
        teamPlayerService.add(team1.id, player1.id, 1)
        teamPlayerService.add(team1.id, player2.id, 2)
        teamPlayerService.add(team2.id, opponent.id, 3)
        match = Match(UUID.randomUUID(), listOf(team1.id, team2.id))
        matchService.create(match)
        matchService.startMatch(match.id, MATCH_STARTED_AT)
    }

    @Test
    fun `valid event creation in planned match returns lifecycle conflict`() {
        val plannedMatch = createPlannedMatch()

        mockMvc.perform(postEvent(validPull(), plannedMatch.id))
            .andExpectProblem(
                expectedStatus = 409,
                code = "MATCH_NOT_IN_PROGRESS",
                instance = "/api/v1/matches/${plannedMatch.id}/events",
                currentStatus = "PLANNED",
            )
    }

    @Test
    fun `valid event creation in progress returns 201`() {
        mockMvc.perform(postEvent(validPull()))
            .andExpect(status().isCreated)
    }

    @Test
    fun `pass cannot be the first gameplay event`() {
        mockMvc.perform(postEvent(mapOf(
            "type" to "PASS",
            "occurredAt" to EVENT_OCCURRED_AT,
            "fromParticipantId" to player1.id,
            "toParticipantId" to player2.id,
        )))
            .andExpectProblem(
                expectedStatus = 409,
                code = "EVENT_SEQUENCE_VIOLATION",
                instance = "/api/v1/matches/${match.id}/events",
                currentState = "BEFORE_FIRST_PULL",
                attemptedEventType = "PASS",
            )
    }

    @Test
    fun `generic block can be refined after creation`() {
        createAndGetId(validPull(EVENT_OCCURRED_AT))
        createAndGetId(validPickup(EVENT_OCCURRED_AT.plusSeconds(1)))
        val blockId = createAndGetId(mapOf(
            "type" to "BLOCK",
            "occurredAt" to EVENT_OCCURRED_AT.plusSeconds(2),
            "fromParticipantId" to player1.id,
            "toParticipantId" to opponent.id,
        ))

        mockMvc.perform(
            patch("/api/v1/matches/${match.id}/events/$blockId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf(
                    "type" to "BLOCK_FIELD",
                ))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("BLOCK_FIELD"))
            .andExpect(jsonPath("$.fromParticipantId").value(player1.id.toString()))
            .andExpect(jsonPath("$.toParticipantId").value(opponent.id.toString()))
    }

    @Test
    fun `event before match start returns timestamp conflict`() {
        mockMvc.perform(postEvent(validPull(MATCH_STARTED_AT.minusSeconds(1))))
            .andExpectProblem(
                expectedStatus = 409,
                code = "EVENT_BEFORE_START",
                instance = "/api/v1/matches/${match.id}/events",
            )
    }

    @Test
    fun `event before latest active event returns chronology conflict`() {
        createAndGetId(validPull(Instant.parse("2026-07-14T10:01:00Z")))

        mockMvc.perform(postEvent(validPull(Instant.parse("2026-07-14T10:00:00Z"))))
            .andExpectProblem(
                expectedStatus = 409,
                code = "EVENT_OUT_OF_ORDER",
                instance = "/api/v1/matches/${match.id}/events",
            )
    }

    @Test
    fun `valid event creation in finished match returns lifecycle conflict`() {
        val finishedMatch = createFinishedMatch()

        mockMvc.perform(postEvent(validPull(), finishedMatch.id))
            .andExpectProblem(
                expectedStatus = 409,
                code = "MATCH_NOT_IN_PROGRESS",
                instance = "/api/v1/matches/${finishedMatch.id}/events",
                currentStatus = "FINISHED",
            )
    }

    @Test
    fun `patch and delete in planned match return lifecycle conflict`() {
        val plannedMatch = createPlannedMatch()
        val eventId = persistLegacyEvent(plannedMatch.id)

        mockMvc.perform(patchEvent(plannedMatch.id, eventId, player2.id))
            .andExpectProblem(
                expectedStatus = 409,
                code = "MATCH_NOT_IN_PROGRESS",
                instance = "/api/v1/matches/${plannedMatch.id}/events/$eventId",
                currentStatus = "PLANNED",
            )
        mockMvc.perform(delete("/api/v1/matches/${plannedMatch.id}/events/$eventId"))
            .andExpectProblem(
                expectedStatus = 409,
                code = "MATCH_NOT_IN_PROGRESS",
                instance = "/api/v1/matches/${plannedMatch.id}/events/$eventId",
                currentStatus = "PLANNED",
            )
    }

    @Test
    fun `invalid semantic patch in planned match remains bad request`() {
        val plannedMatch = createPlannedMatch()
        val eventId = persistLegacyEvent(plannedMatch.id)

        mockMvc.perform(
            patch("/api/v1/matches/${plannedMatch.id}/events/$eventId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf(
                    "type" to "INCOMPLETE_PASS",
                    "participantId" to player1.id,
                ))),
        ).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${plannedMatch.id}/events/$eventId",
            title = "Invalid event request",
            detail = "Event type INCOMPLETE_PASS cannot replace PICKUP",
        )
    }

    @Test
    fun `patch identifies participant outside match`() {
        val eventId = createAndGetId(validPull())
        val missingParticipantId = UUID.randomUUID()

        mockMvc.perform(
            patch("/api/v1/matches/${match.id}/events/$eventId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf(
                    "type" to "PULL",
                    "participantId" to missingParticipantId,
                ))),
        ).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events/$eventId",
            title = "Invalid event request",
            detail = "Participant $missingParticipantId is not part of match ${match.id}",
        )
    }

    @Test
    fun `unsupported system event patch in planned match remains method not allowed`() {
        val plannedMatch = createPlannedMatch()
        val eventId = persistLegacyEvent(
            plannedMatch.id,
            SystemEvent(EVENT_OCCURRED_AT, EventType.HALFTIME_START),
        )

        mockMvc.perform(
            patch("/api/v1/matches/${plannedMatch.id}/events/$eventId")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"HALFTIME_START"}"""),
        ).andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `patch and delete in finished match remain successful`() {
        createAndGetId(validPull(EVENT_OCCURRED_AT))
        createAndGetId(validPickup(EVENT_OCCURRED_AT.plusSeconds(1)))
        val eventId = createAndGetId(mapOf(
            "type" to "PASS",
            "occurredAt" to EVENT_OCCURRED_AT.plusSeconds(2),
            "fromParticipantId" to player1.id,
            "toParticipantId" to player2.id,
        ))
        createAndGetId(mapOf(
            "type" to "GOAL",
            "occurredAt" to EVENT_OCCURRED_AT.plusSeconds(3),
            "fromParticipantId" to player2.id,
            "toParticipantId" to player1.id,
        ))
        matchService.endMatch(match.id, MATCH_ENDED_AT)
        val replacement = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .first { it.kind == MatchParticipantKind.UNKNOWN }

        mockMvc.perform(patchTwoPlayerEvent(UUID.fromString(eventId), toParticipantId = replacement.participantId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.toParticipantId").value(replacement.participantId.toString()))
        mockMvc.perform(delete("/api/v1/matches/${match.id}/events/$eventId"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `concurrent complementary partial patches preserve both participant changes`() {
        val unknowns = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .filter { it.kind == MatchParticipantKind.UNKNOWN }
        createAndGetId(validPull(EVENT_OCCURRED_AT.minusSeconds(2)))
        createAndGetId(validPickup(EVENT_OCCURRED_AT.minusSeconds(1)))
        val eventId = UUID.fromString(createAndGetId(mapOf(
            "type" to "PASS",
            "occurredAt" to EVENT_OCCURRED_AT,
            "fromParticipantId" to unknowns[0].participantId,
            "toParticipantId" to unknowns[1].participantId,
        )))
        val staleReads = CountDownLatch(2)
        Mockito.doAnswer { invocation ->
            val stored = invocation.callRealMethod()
            staleReads.countDown()
            check(staleReads.await(5, TimeUnit.SECONDS)) { "Concurrent PATCH requests did not both read the event" }
            stored
        }.`when`(eventService).get(eventId, match.id)

        Executors.newFixedThreadPool(2).use { executor ->
            val fromPatch = executor.submit<MvcResult> {
                mockMvc.perform(patchTwoPlayerEvent(eventId, fromParticipantId = player1.id)).andReturn()
            }
            val toPatch = executor.submit<MvcResult> {
                mockMvc.perform(patchTwoPlayerEvent(eventId, toParticipantId = player2.id)).andReturn()
            }

            assertEquals(200, fromPatch.get(10, TimeUnit.SECONDS).response.status)
            assertEquals(200, toPatch.get(10, TimeUnit.SECONDS).response.status)
        }
        Mockito.reset(eventService)

        mockMvc.perform(get("/api/v1/matches/${match.id}/events/$eventId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fromParticipantId").value(player1.id.toString()))
            .andExpect(jsonPath("$.toParticipantId").value(player2.id.toString()))
    }

    @Test
    fun `missing match and event mutations still return not found`() {
        val missingMatchId = UUID.randomUUID()
        val missingEventId = UUID.randomUUID()

        mockMvc.perform(postEvent(validPull(), missingMatchId))
            .andExpectProblem(
                expectedStatus = 404,
                code = "RESOURCE_NOT_FOUND",
                instance = "/api/v1/matches/$missingMatchId/events",
            )
        mockMvc.perform(patchEvent(match.id, missingEventId, player2.id))
            .andExpectProblem(
                expectedStatus = 404,
                code = "RESOURCE_NOT_FOUND",
                instance = "/api/v1/matches/${match.id}/events/$missingEventId",
            )
    }

    @Test
    fun `deleting missing event in planned match returns not found`() {
        val plannedMatch = createPlannedMatch()
        val missingEventId = UUID.randomUUID()

        mockMvc.perform(delete("/api/v1/matches/${plannedMatch.id}/events/$missingEventId"))
            .andExpectProblem(
                expectedStatus = 404,
                code = "RESOURCE_NOT_FOUND",
                instance = "/api/v1/matches/${plannedMatch.id}/events/$missingEventId",
            )
    }

    @Test
    fun `one-player event has only its category fields`() {
        mockMvc.perform(postEvent(mapOf("type" to "PULL", "occurredAt" to "2026-07-14T10:00:00Z", "participantId" to player1.id)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").isNotEmpty)
            .andExpect(jsonPath("$.sequenceNumber").value(1))
            .andExpect(jsonPath("$.participantId").value(player1.id.toString()))
            .andExpect(jsonPath("$.teamId").doesNotExist())
            .andExpect(jsonPath("$._eventClass").doesNotExist())
    }

    @Test
    fun `two-player request rejects players from wrong team for pass`() {
        mockMvc.perform(postEvent(mapOf(
            "type" to "PASS", "occurredAt" to "2026-07-14T10:00:00Z",
            "fromParticipantId" to player1.id, "toParticipantId" to opponent.id,
        ))).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events",
            title = "Invalid event request",
            detail = "PASS requires participants from the same match team",
        )
    }

    @Test
    fun `two-player request explains that participants must be different`() {
        mockMvc.perform(postEvent(mapOf(
            "type" to "PASS", "occurredAt" to "2026-07-14T10:00:00Z",
            "fromParticipantId" to player1.id, "toParticipantId" to player1.id,
        ))).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events",
            title = "Invalid event request",
            detail = "Event participants must be different",
        )
    }

    @Test
    fun `one-player request identifies participant outside match`() {
        val missingParticipantId = UUID.randomUUID()

        mockMvc.perform(postEvent(mapOf(
            "type" to "PULL", "occurredAt" to "2026-07-14T10:00:00Z",
            "participantId" to missingParticipantId,
        ))).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events",
            title = "Invalid event request",
            detail = "Participant $missingParticipantId is not part of match ${match.id}",
        )
    }

    @Test
    fun `team event identifies team outside match`() {
        val missingTeamId = UUID.randomUUID()

        mockMvc.perform(postEvent(mapOf(
            "type" to "TIMEOUT_START", "occurredAt" to "2026-07-14T10:00:00Z",
            "teamId" to missingTeamId,
        ))).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events",
            title = "Invalid event request",
            detail = "Team $missingTeamId is not part of match ${match.id}",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{\"type\":\"PICKUP\"",
        "{\"type\":\"UNKNOWN\",\"occurredAt\":\"2026-07-14T10:00:00Z\",\"participantId\":\"11111111-1111-1111-1111-111111111111\"}",
        "{\"type\":\"DROP\",\"occurredAt\":\"2026-07-14T10:00:00Z\",\"participantId\":\"11111111-1111-1111-1111-111111111111\"}",
        "{\"type\":\"TURNOVER\",\"occurredAt\":\"2026-07-14T10:00:00Z\",\"participantId\":\"11111111-1111-1111-1111-111111111111\"}",
        "{\"type\":\"PICKUP\",\"occurredAt\":\"2026-07-14T10:00:00Z\"}",
    ])
    fun `invalid event JSON returns INVALID_REQUEST ProblemDetail`(body: String) {
        mockMvc.perform(
            post("/api/v1/matches/${match.id}/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpectProblem(
            expectedStatus = 400,
            code = "INVALID_REQUEST",
            instance = "/api/v1/matches/${match.id}/events",
            title = "Invalid request body",
            detail = "Request body is malformed or contains unsupported values",
        )
    }

    @Test
    fun `team and system event schemas deserialize by type`() {
        mockMvc.perform(postEvent(mapOf("type" to "TIMEOUT_START", "occurredAt" to "2026-07-14T10:00:00Z", "teamId" to team1.id)))
            .andExpect(status().isCreated).andExpect(jsonPath("$.teamId").value(team1.id.toString()))
        createAndGetId(mapOf("type" to "TIMEOUT_END", "occurredAt" to "2026-07-14T10:00:01Z", "teamId" to team1.id))
        createAndGetId(validPull(Instant.parse("2026-07-14T10:00:02Z")))
        createAndGetId(validPickup(Instant.parse("2026-07-14T10:00:03Z")))
        createAndGetId(mapOf(
            "type" to "GOAL",
            "occurredAt" to "2026-07-14T10:00:04Z",
            "fromParticipantId" to player1.id,
            "toParticipantId" to player2.id,
        ))
        mockMvc.perform(postEvent(mapOf("type" to "HALFTIME_START", "occurredAt" to "2026-07-14T10:01:00Z")))
            .andExpect(status().isCreated).andExpect(jsonPath("$.teamId").doesNotExist())
    }

    @Test
    fun `event OpenAPI schemas map runtime types to their shapes`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.propertyName").value("type"))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.length()").value(15))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.PICKUP").value("#/components/schemas/OnePlayerEventResponse"))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.BLOCK").value("#/components/schemas/TwoPlayerEventResponse"))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.PASS").value("#/components/schemas/TwoPlayerEventResponse"))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.TIMEOUT_START").value("#/components/schemas/TeamEventResponse"))
            .andExpect(jsonPath("$.components.schemas.EventResponse.discriminator.mapping.HALFTIME_START").value("#/components/schemas/SystemEventResponse"))
            .andExpect(jsonPath("$.components.schemas.CreateEventRequest.discriminator.mapping.length()").value(15))
            .andExpect(jsonPath("$.components.schemas.UpdateEventRequest.discriminator.mapping.length()").value(13))
            .andExpect(jsonPath("$.components.schemas.UpdateEventRequest.discriminator.mapping.PICKUP").value("#/components/schemas/OnePlayerEventPatchRequest"))
            .andExpect(jsonPath("$.components.schemas.UpdateEventRequest.discriminator.mapping.BLOCK_MARKER").value("#/components/schemas/TwoPlayerEventPatchRequest"))
            .andExpect(jsonPath("$.components.schemas.UpdateEventRequest.discriminator.mapping.TIMEOUT_START").value("#/components/schemas/TeamEventPatchRequest"))
    }

    @Test
    fun `create event OpenAPI request has an exact payload example for every event type`() {
        val document = objectMapper.readTree(
            mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val operation = document["paths"]["/api/v1/matches/{matchId}/events"]["post"]
        val examples = operation["requestBody"]["content"]["application/json"]["examples"]
        val commonFields = setOf("type", "occurredAt")
        val expectedFields = mapOf(
            "PASS" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "GOAL" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "INCOMPLETE_PASS" to commonFields + "participantId",
            "PULL" to commonFields + "participantId",
            "BRICK" to commonFields + "participantId",
            "PICKUP" to commonFields + "participantId",
            "BLOCK" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "BLOCK_MARKER" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "BLOCK_FIELD" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "INTERCEPTION" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "CALLAHAN" to commonFields + setOf("fromParticipantId", "toParticipantId"),
            "TIMEOUT_START" to commonFields + "teamId",
            "TIMEOUT_END" to commonFields + "teamId",
            "HALFTIME_START" to commonFields,
            "HALFTIME_END" to commonFields,
        )

        assertEquals(expectedFields.keys, examples.fieldNames().asSequence().toSet())
        expectedFields.forEach { (type, fields) ->
            val example = examples[type]["value"]
            assertEquals(type, example["type"].asText())
            assertEquals(fields, example.fieldNames().asSequence().toSet(), "Unexpected fields in $type example")
            assertTrue(examples[type]["description"].asText().isNotBlank(), "$type must explain event semantics")
        }
        assertTrue(operation["description"].asText().contains("fromParticipantId"))
        assertTrue(operation["description"].asText().contains("toParticipantId"))
    }

    @Test
    fun `event request schemas explain participant roles`() {
        val document = objectMapper.readTree(
            mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val schemas = document["components"]["schemas"]
        val onePlayerProperties = schemas["OnePlayerEventRequest"]["allOf"][1]["properties"]
        val twoPlayerProperties = schemas["TwoPlayerEventRequest"]["allOf"][1]["properties"]
        val teamProperties = schemas["TeamEventRequest"]["allOf"][1]["properties"]

        assertTrue(onePlayerProperties["participantId"]["description"].asText().isNotBlank())
        assertTrue(twoPlayerProperties["fromParticipantId"]["description"].asText().contains("бросающ"))
        assertTrue(twoPlayerProperties["toParticipantId"]["description"].asText().isNotBlank())
        assertTrue(teamProperties["teamId"]["description"].asText().isNotBlank())
    }

    @Test
    fun `update event OpenAPI documents allowed corrections with examples`() {
        val document = objectMapper.readTree(
            mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString,
        )
        val operation = document["paths"]["/api/v1/matches/{matchId}/events/{eventId}"]["patch"]
        val description = operation["description"].asText()
        val examples = operation["requestBody"]["content"]["application/json"]["examples"]

        assertTrue(description.contains("occurredAt"))
        assertTrue(description.contains("HALFTIME_START"))
        assertTrue(description.contains("BLOCK_MARKER"))
        assertEquals(
            setOf("onePlayerEvent", "twoPlayerEvent", "blockTypeCorrection", "teamEvent"),
            examples.fieldNames().asSequence().toSet(),
        )
        assertEquals("PICKUP", examples["onePlayerEvent"]["value"]["type"].asText())
        assertEquals("PASS", examples["twoPlayerEvent"]["value"]["type"].asText())
        assertEquals("BLOCK_MARKER", examples["blockTypeCorrection"]["value"]["type"].asText())
        assertEquals("TIMEOUT_START", examples["teamEvent"]["value"]["type"].asText())
    }

    @Test
    fun `event mutations document bad request ProblemDetail`() {
        val eventsPath = "$.paths['/api/v1/matches/{matchId}/events']"
        val eventPath = "$.paths['/api/v1/matches/{matchId}/events/{eventId}']"

        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$eventsPath.post.responses['400'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
            .andExpect(
                jsonPath("$eventPath.patch.responses['400'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
    }

    @Test
    fun `frontend origin is allowed by CORS`() {
        mockMvc.perform(
            options("/api/v1/matches")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
    }

    @Test
    fun `event is read patched and deleted by UUID`() {
        val created = mockMvc.perform(postEvent(mapOf("type" to "PULL", "occurredAt" to "2026-07-14T10:00:00Z", "participantId" to player1.id)))
            .andReturn().response.contentAsString
        val eventId = objectMapper.readTree(created).get("id").asText()

        mockMvc.perform(get("/api/v1/matches/${match.id}/events/$eventId"))
            .andExpect(status().isOk).andExpect(jsonPath("$.participantId").value(player1.id.toString()))
        mockMvc.perform(patch("/api/v1/matches/${match.id}/events/$eventId")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("type" to "PULL", "participantId" to player2.id))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.participantId").value(player2.id.toString()))
            .andExpect(jsonPath("$.occurredAt").value("2026-07-14T10:00:00Z"))
        mockMvc.perform(delete("/api/v1/matches/${match.id}/events/$eventId"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/matches/${match.id}/events/$eventId")).andExpect(status().isNotFound)
    }

    @Test
    fun `event type cannot change and system event cannot be patched`() {
        val playerEvent = createAndGetId(mapOf("type" to "PULL", "occurredAt" to "2026-07-14T10:00:00Z", "participantId" to player1.id))
        mockMvc.perform(patch("/api/v1/matches/${match.id}/events/$playerEvent")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("type" to "INCOMPLETE_PASS", "participantId" to player1.id))))
            .andExpect(status().isBadRequest)

        mockMvc.perform(delete("/api/v1/matches/${match.id}/events/$playerEvent"))
            .andExpect(status().isNoContent)
        createAndGetId(validPull(Instant.parse("2026-07-14T10:00:01Z")))
        createAndGetId(validPickup(Instant.parse("2026-07-14T10:00:02Z")))
        createAndGetId(mapOf(
            "type" to "GOAL",
            "occurredAt" to "2026-07-14T10:00:03Z",
            "fromParticipantId" to player1.id,
            "toParticipantId" to player2.id,
        ))
        val systemEvent = createAndGetId(mapOf("type" to "HALFTIME_START", "occurredAt" to "2026-07-14T10:01:00Z"))
        mockMvc.perform(patch("/api/v1/matches/${match.id}/events/$systemEvent")
            .contentType(MediaType.APPLICATION_JSON).content("""{"type":"HALFTIME_START"}"""))
            .andExpect(status().isMethodNotAllowed)
    }

    @Test
    fun `pass between two unknown participants can be corrected to real participants`() {
        val unknowns = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .filter { it.kind == MatchParticipantKind.UNKNOWN }
        createAndGetId(validPull(EVENT_OCCURRED_AT.minusSeconds(2)))
        createAndGetId(validPickup(EVENT_OCCURRED_AT.minusSeconds(1)))
        val created = mockMvc.perform(postEvent(mapOf(
            "type" to "PASS",
            "occurredAt" to "2026-07-14T10:00:00Z",
            "fromParticipantId" to unknowns[0].participantId,
            "toParticipantId" to unknowns[1].participantId,
        )))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.fromParticipantId").value(unknowns[0].participantId.toString()))
            .andExpect(jsonPath("$.toParticipantId").value(unknowns[1].participantId.toString()))
            .andReturn().response.contentAsString
        val eventId = objectMapper.readTree(created).get("id").asText()

        val unresolved = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(match.id))
        assertEquals(1, unresolved.playerStatistics.single { it.participantId == unknowns[0].participantId }.attack.passes)
        assertEquals(1, unresolved.playerStatistics.single { it.participantId == unknowns[1].participantId }.attack.catches)

        mockMvc.perform(patch("/api/v1/matches/${match.id}/events/$eventId")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf(
                "type" to "PASS",
                "fromParticipantId" to player1.id,
            ))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fromParticipantId").value(player1.id.toString()))
            .andExpect(jsonPath("$.toParticipantId").value(unknowns[1].participantId.toString()))

        val partiallyCorrected = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(match.id))
        assertEquals(0, partiallyCorrected.playerStatistics.single { it.participantId == unknowns[0].participantId }.attack.passes)
        assertEquals(1, partiallyCorrected.playerStatistics.single { it.participantId == unknowns[1].participantId }.attack.catches)
        assertEquals(1, partiallyCorrected.playerStatistics.single { it.participantId == player1.id }.attack.passes)

        mockMvc.perform(patch("/api/v1/matches/${match.id}/events/$eventId")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf(
                "type" to "PASS",
                "toParticipantId" to player2.id,
            ))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fromParticipantId").value(player1.id.toString()))
            .andExpect(jsonPath("$.toParticipantId").value(player2.id.toString()))

        val corrected = statisticsService.recalculateMatchStatistics(matchService.getOrThrow(match.id))
        assertEquals(0, corrected.playerStatistics.single { it.participantId == unknowns[0].participantId }.attack.passes)
        assertEquals(0, corrected.playerStatistics.single { it.participantId == unknowns[1].participantId }.attack.catches)
        assertEquals(1, corrected.playerStatistics.single { it.participantId == player1.id }.attack.passes)
        assertEquals(1, corrected.playerStatistics.single { it.participantId == player2.id }.attack.catches)
    }

    @Test
    fun `one-participant event accepts unknown participant`() {
        val unknown = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .first { it.kind == MatchParticipantKind.UNKNOWN }

        mockMvc.perform(postEvent(mapOf(
            "type" to "PULL",
            "occurredAt" to "2026-07-14T10:00:00Z",
            "participantId" to unknown.participantId,
        )))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.participantId").value(unknown.participantId.toString()))
    }

    private fun postEvent(body: Map<String, Any>, matchId: UUID = match.id) = post("/api/v1/matches/$matchId/events")
        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private fun createAndGetId(body: Map<String, Any>): String = objectMapper.readTree(
        mockMvc.perform(postEvent(body)).andExpect(status().isCreated).andReturn().response.contentAsString
    ).get("id").asText()

    private fun validPull(occurredAt: Instant = EVENT_OCCURRED_AT): Map<String, Any> = mapOf(
        "type" to "PULL",
        "occurredAt" to occurredAt,
        "participantId" to opponent.id,
    )

    private fun validPickup(occurredAt: Instant = EVENT_OCCURRED_AT): Map<String, Any> = mapOf(
        "type" to "PICKUP",
        "occurredAt" to occurredAt,
        "participantId" to player1.id,
    )

    private fun createPlannedMatch(): Match = Match(UUID.randomUUID(), listOf(team1.id, team2.id)).also(matchService::create)

    private fun createFinishedMatch(): Match = createPlannedMatch().also { finishedMatch ->
        matchService.startMatch(finishedMatch.id, MATCH_STARTED_AT)
        eventService.create(
            OnePlayerEvent(opponent.id, EVENT_OCCURRED_AT, EventType.PULL),
            finishedMatch.id,
        )
        eventService.create(
            OnePlayerEvent(player1.id, EVENT_OCCURRED_AT.plusSeconds(1), EventType.PICKUP),
            finishedMatch.id,
        )
        eventService.create(
            com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent(
                player1.id,
                player2.id,
                EVENT_OCCURRED_AT.plusSeconds(2),
                EventType.GOAL,
            ),
            finishedMatch.id,
        )
        matchService.endMatch(finishedMatch.id, MATCH_ENDED_AT)
    }

    private fun persistLegacyEvent(
        matchId: UUID,
        event: Event = OnePlayerEvent(player1.id, EVENT_OCCURRED_AT, EventType.PICKUP),
    ): UUID {
        val eventId = UUID.randomUUID()
        eventFixture.persist(
            EventEntity.fromDomain(eventId, matchId, 1, event),
        )
        return eventId
    }

    private fun patchEvent(matchId: UUID, eventId: Any, participantId: UUID) =
        patch("/api/v1/matches/$matchId/events/$eventId")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("type" to "PICKUP", "participantId" to participantId)))

    private fun patchTwoPlayerEvent(
        eventId: UUID,
        fromParticipantId: UUID? = null,
        toParticipantId: UUID? = null,
    ) = patch("/api/v1/matches/${match.id}/events/$eventId")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf(
            "type" to "PASS",
            "fromParticipantId" to fromParticipantId,
            "toParticipantId" to toParticipantId,
        )))

    private fun org.springframework.test.web.servlet.ResultActions.andExpectProblem(
        expectedStatus: Int,
        code: String,
        instance: String,
        title: String? = null,
        detail: String? = null,
        currentStatus: String? = null,
        currentState: String? = null,
        attemptedEventType: String? = null,
    ) = apply {
        andExpect(status().`is`(expectedStatus))
        andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        andExpect(jsonPath("$.status").value(expectedStatus))
        andExpect(jsonPath("$.code").value(code))
        andExpect(jsonPath("$.instance").value(instance))
        title?.let { andExpect(jsonPath("$.title").value(it)) }
        detail?.let { andExpect(jsonPath("$.detail").value(it)) }
        if (currentStatus == null) {
            andExpect(jsonPath("$.currentStatus").doesNotExist())
        } else {
            andExpect(jsonPath("$.currentStatus").value(currentStatus))
        }
        if (currentState == null) {
            andExpect(jsonPath("$.currentState").doesNotExist())
        } else {
            andExpect(jsonPath("$.currentState").value(currentState))
        }
        if (attemptedEventType == null) {
            andExpect(jsonPath("$.attemptedEventType").doesNotExist())
        } else {
            andExpect(jsonPath("$.attemptedEventType").value(attemptedEventType))
        }
    }

    companion object {
        private val MATCH_STARTED_AT = Instant.parse("2026-07-14T09:00:00Z")
        private val EVENT_OCCURRED_AT = Instant.parse("2026-07-14T10:00:00Z")
        private val MATCH_ENDED_AT = Instant.parse("2026-07-14T11:00:00Z")
    }
}

@TestConfiguration
class EventControllerTestConfiguration {
    @Bean
    fun eventControllerTestFixture(eventRepository: SpringDataEventRepository) =
        EventControllerTestFixture(eventRepository)
}

class EventControllerTestFixture(
    private val eventRepository: SpringDataEventRepository,
) {
    fun persist(event: EventEntity) {
        eventRepository.save(event)
    }
}
