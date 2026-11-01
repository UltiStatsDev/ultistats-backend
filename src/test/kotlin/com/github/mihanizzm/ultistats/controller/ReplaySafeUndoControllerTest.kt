package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
class ReplaySafeUndoControllerTest : MatchAbstractTest() {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    private lateinit var match: Match
    private val at = Instant.parse("2026-10-24T10:00:00Z")
    private fun path(id: UUID = match.id) = "/api/v2/matches/$id/events/undo"

    @BeforeEach fun uniqueMatch() {
        match = Match(UUID.randomUUID(), listOf(TEAM_1.id, TEAM_2.id))
        matchService.create(match)
        assertIs<MatchCommandResult.Success<Match>>(matchService.startMatch(match.id, at))
    }

    @Test fun `committed duplicate returns identical outcome and recovery without deleting next tail`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val pickup = record(OnePlayerEvent(PLAYERS_1[0].id, at, EventType.PICKUP))
        val request = envelope(pickup)
        val first = send(request, 200)
        assertEquals(pickup.id.toString(), first["removedEventId"].asText())
        assertEquals(2, first["removedSequenceNumber"].asInt())
        assertEquals(3, first["resultLogRevision"].asLong())
        assertEquals("APPLIED", first["outcome"].asText())
        assertTrue(first["rejection"].isNull)
        assertEquals(first, send(request, 200))
        assertEquals(first, lookup(request["commandId"], 200))
        assertEquals(listOf(pull.id), eventService.snapshot(match.id)!!.events.map { it.id })
        assertEquals(3, eventService.snapshot(match.id)!!.logRevision)
        // Receipt is historical even after another command changes the canonical log.
        record(OnePlayerEvent(PLAYERS_1[0].id, at, EventType.PICKUP))
        assertEquals(first, send(request, 200))
        assertEquals(first, lookup(request["commandId"], 200))
        assertEquals(4, eventService.snapshot(match.id)!!.logRevision)
    }

    @Test fun `same identity changed valid payload conflicts and cannot replace receipt`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val request = envelope(pull)
        val first = send(request, 200)
        val changed = request + ("expectedLastEventId" to UUID.randomUUID())
        assertEquals("UNDO_COMMAND_CONFLICT", send(changed, 409)["code"].asText())
        assertEquals("UNDO_COMMAND_CONFLICT", send(request + ("expectedLogRevision" to 0), 409)["code"].asText())
        assertEquals(first, lookup(request["commandId"], 200))
        assertEquals(2, eventService.snapshot(match.id)!!.logRevision)
    }

    @Test fun `stale rejection is durable and cannot later become applied`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val request = envelope(pull) + ("expectedLastEventId" to UUID.randomUUID())
        val rejection = send(request, 409)
        assertEquals("EVENT_LOG_STALE", rejection["code"].asText())
        assertEquals("REJECTED", rejection["commandResult"]["outcome"].asText())
        assertEquals(rejection["commandResult"], lookup(request["commandId"], 200))
        record(OnePlayerEvent(PLAYERS_1[0].id, at, EventType.PICKUP))
        assertEquals(rejection, send(request, 409))
        assertEquals(2, eventService.snapshot(match.id)!!.logRevision)
        assertEquals(2, eventService.snapshot(match.id)!!.events.size)
        assertEquals("UNDO_COMMAND_CONFLICT", send(envelope(eventService.getAllEventsOfMatch(match.id).last()) + ("commandId" to request["commandId"]),409)["code"].asText())
    }

    @Test fun `noninteger or coerced revision cannot mutate or reserve identity`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val command = envelope(pull)
        for (invalidRevision in listOf(1.5, "1", true)) {
            assertEquals("INVALID_REQUEST", send(command + ("expectedLogRevision" to invalidRevision), 400)["code"].asText())
            lookup(command["commandId"], 404)
            assertEquals(listOf(pull), eventService.getAllEventsOfMatch(match.id))
            assertEquals(1, eventService.snapshot(match.id)!!.logRevision)
        }
        assertEquals("APPLIED", send(command, 200)["outcome"].asText())
    }

    @Test fun `revision stale with matching tail also rejects`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val request = envelope(pull) + ("expectedLogRevision" to 0)
        assertEquals("EVENT_LOG_STALE", send(request, 409)["code"].asText())
        assertEquals(listOf(pull), eventService.getAllEventsOfMatch(match.id))
    }

    @Test fun `empty missing deleted and invalid requests have explicit results`() {
        val empty = mapOf("commandId" to UUID.randomUUID(), "expectedLastEventId" to UUID.randomUUID(), "expectedLogRevision" to 0)
        val rejection = send(empty, 409)
        assertEquals("UNDO_LOG_EMPTY", rejection["code"].asText())
        assertEquals(rejection["commandResult"],lookup(empty["commandId"],200))
        assertEquals("RESOURCE_NOT_FOUND", lookup(UUID.randomUUID(),404)["code"].asText())
        for (invalid in listOf(empty - "commandId", empty - "expectedLastEventId", empty - "expectedLogRevision", empty + ("expectedLogRevision" to -1), empty + ("commandId" to null))) {
            assertEquals("INVALID_REQUEST",send(invalid,400)["code"].asText())
        }
        send(empty,404,UUID.randomUUID())
        matchService.delete(match.id)
        send(empty,404)
        lookup(empty["commandId"],404)
    }

    @Test fun `finished policy rejection is recoverable and leaves goal and score`() {
        record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        record(OnePlayerEvent(PLAYERS_1[0].id, at, EventType.PICKUP))
        val goal=record(TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, at, EventType.GOAL))
        assertIs<MatchCommandResult.Success<Match>>(matchService.endMatch(match.id,at.plusSeconds(1)))
        val request=envelope(goal)
        assertEquals("EVENT_SEQUENCE_VIOLATION",send(request,409)["code"].asText())
        assertEquals(goal,eventService.get(goal.id,match.id))
        assertEquals(1,matchService.getOrThrow(match.id).teamScores.single { it.teamId==TEAM_1.id }.score)
    }

    @Test fun `concurrent identical requests remove one UUID at one revision`() {
        val pull=record(OnePlayerEvent(PLAYERS_2[0].id,at,EventType.PULL))
        val pickup=record(OnePlayerEvent(PLAYERS_1[0].id,at,EventType.PICKUP))
        val request=envelope(pickup)
        val pool=Executors.newFixedThreadPool(4)
        val gate=CountDownLatch(1)
        try {
            val futures=(1..4).map { pool.submit<JsonNode> { check(gate.await(5,TimeUnit.SECONDS));send(request,200) } }
            gate.countDown()
            val outcomes=futures.map { it.get(10,TimeUnit.SECONDS) }
            assertEquals(1,outcomes.distinct().size)
            assertEquals(listOf(pull),eventService.getAllEventsOfMatch(match.id))
            assertEquals(3,eventService.snapshot(match.id)!!.logRevision)
        } finally { gate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun `goal undo changes score once and never reuses sequence`() {
        record(OnePlayerEvent(PLAYERS_2[0].id,at,EventType.PULL))
        record(OnePlayerEvent(PLAYERS_1[0].id,at,EventType.PICKUP))
        val goal=record(TwoPlayerEvent(PLAYERS_1[0].id,PLAYERS_1[1].id,at,EventType.GOAL))
        val request=envelope(goal)
        send(request,200);send(request,200)
        assertEquals(0,matchService.getOrThrow(match.id).teamScores.single { it.teamId==TEAM_1.id }.score)
        assertEquals(4,eventService.snapshot(match.id)!!.logRevision)
        val replacement=record(TwoPlayerEvent(PLAYERS_1[0].id,PLAYERS_1[1].id,at,EventType.GOAL))
        assertEquals(4,replacement.sequenceNumber)
    }

    @Test fun `export actual HTTP outcomes for raw schema and generated consumer verification`() {
        val pull = record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val command = envelope(pull)
        val fixtures = mutableListOf<Map<String, Any>>()
        fun fixture(schema: String, payload: Any, status: Int) {
            fixtures += mapOf("schema" to schema, "payload" to payload, "status" to status)
        }
        fixture("UndoEventRequest", command, 0)
        fixture("UndoCommandResponse", send(command, 200), 200)
        fixture("UndoCommandResponse", send(command, 200), 200)
        fixture("UndoCommandResponse", lookup(command["commandId"], 200), 200)
        fixture("UndoCommandProblem", send(command + ("expectedLogRevision" to 0), 409), 409)
        val empty = command + ("commandId" to UUID.randomUUID())
        val rejection = send(empty, 409)
        fixture("UndoCommandProblem", rejection, 409)
        fixture("UndoCommandResponse", rejection["commandResult"], 409)
        fixture("UndoCommandResponse", lookup(empty["commandId"], 200), 200)
        fixture("ProblemDetail", send(command - "commandId", 400), 400)
        fixture("ProblemDetail", send(command, 404, UUID.randomUUID()), 404)
        fixture("ProblemDetail", lookup(UUID.randomUUID(), 404), 404)
        val output = java.nio.file.Path.of("build/contracts/undo144")
        java.nio.file.Files.createDirectories(output)
        java.nio.file.Files.writeString(output.resolve("fixtures.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixtures))
    }

    @Test fun `raw OpenAPI describes required nullable results and typed conflicts`() {
        val api = mapper.readTree(mvc.perform(get("/v3/api-docs")).andReturn().response.contentAsString)
        val schemas = api["components"]["schemas"]
        val response = schemas["UndoCommandResponse"]
        assertEquals(setOf("matchId", "commandId", "expectedLastEventId", "expectedLogRevision", "outcome", "resultLogRevision", "removedEventId", "removedSequenceNumber", "rejection"), response["required"].map { it.asText() }.toSet())
        assertEquals(setOf("string", "null"), response["properties"]["removedEventId"]["type"].map { it.asText() }.toSet())
        assertEquals("null", response["properties"]["rejection"]["anyOf"]?.get(1)?.get("type")?.asText())
        val request = schemas["UndoEventRequest"]
        assertEquals(setOf("commandId", "expectedLastEventId", "expectedLogRevision"), request["required"].map { it.asText() }.toSet())
        assertEquals("integer", request["properties"]["expectedLogRevision"]["type"].asText())
        assertEquals("#/components/schemas/UndoCommandProblem", api["paths"]["/api/v2/matches/{matchId}/events/undo"]["post"]["responses"]["409"]["content"]["application/problem+json"]["schema"]["\$ref"].asText())
        val output = java.nio.file.Path.of("build/contracts/undo144")
        java.nio.file.Files.createDirectories(output)
        java.nio.file.Files.writeString(output.resolve("openapi.json"), api.toPrettyString())
    }

    private fun record(event: Event)=assertIs<EventCommandResult.Success>(eventService.create(event,match.id)).event
    private fun envelope(tail: StoredEvent): Map<String,Any?> = mapOf("commandId" to UUID.randomUUID(),"expectedLastEventId" to tail.id,"expectedLogRevision" to eventService.snapshot(match.id)!!.logRevision)
    private fun send(body: Map<String,Any?>, expected: Int, id: UUID=match.id): JsonNode {
        val result=mvc.perform(post(path(id)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn().response
        assertEquals(expected,result.status,result.contentAsString)
        return mapper.readTree(result.contentAsString)
    }
    private fun lookup(commandId: Any?,expected:Int): JsonNode {
        val result=mvc.perform(get("${path()}/$commandId")).andReturn().response
        assertEquals(expected,result.status,result.contentAsString)
        return mapper.readTree(result.contentAsString)
    }
}
