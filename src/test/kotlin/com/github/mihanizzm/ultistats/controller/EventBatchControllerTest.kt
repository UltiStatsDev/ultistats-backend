package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.realtime.EventLogChangedSignal
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
open class EventBatchControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @MockitoSpyBean lateinit var matches: MatchService
    @Autowired lateinit var applicationEvents: ApplicationEvents
    @Autowired lateinit var teams: TeamService
    private lateinit var match: Match
    private lateinit var participant: UUID
    private val at = Instant.parse("2026-07-28T12:00:00Z")
    private val path get() = "/api/v2/matches/${match.id}/events"

    @BeforeEach
    fun setup() {
        val one = Team(UUID.randomUUID(), "Batch One")
        val two = Team(UUID.randomUUID(), "Batch Two")
        teams.create(one); teams.create(two)
        match = Match(UUID.randomUUID(), listOf(one.id, two.id))
        matches.create(match)
        matches.startMatch(match.id, at)
        participant = matches.getOrThrow(match.id).participantsByTeam.values.first().first().participantId
    }

    private fun event(type: String, id: UUID = UUID.randomUUID(), time: Instant = at) =
        mapOf("eventId" to id, "type" to type, "occurredAt" to time.toString(), "participantId" to participant)

    private fun batch(events: List<Map<String, Any>>, sequence: Int = 0, revision: Long = 0) =
        mapOf("events" to events, "expectedLastSequenceNumber" to sequence, "expectedLogRevision" to revision)

    private fun send(body: Any, code: Int = 201): JsonNode = mapper.readTree(
        mvc.perform(post("$path/batch").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().`is`(code)).andReturn().response.contentAsString,
    )

    private fun snapshot(): JsonNode = mapper.readTree(
        mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString,
    )

    @Test
    fun `ordered batch assigns canonical numbers and retry is side effect free`() {
        val events = listOf(event("PULL"), event("PICKUP"))
        val body = batch(events)
        clearInvocations(matches)
        val first = send(body)
        verify(matches, times(1)).recalculateScore(match.id)
        assertEquals(1L, applicationEvents.stream(EventLogChangedSignal::class.java).count())
        assertEquals(listOf(1, 2), first["events"].map { it["sequenceNumber"].asInt() })
        assertEquals(events.map { it["eventId"].toString() }, first["events"].map { it["id"].asText() })
        assertEquals(1, first["logRevision"].asInt())
        val retry = send(body, 200)
        assertEquals(first["events"], retry["events"])
        verify(matches, times(1)).recalculateScore(match.id)
        assertEquals(1L, applicationEvents.stream(EventLogChangedSignal::class.java).count())
        assertEquals(1, snapshot()["logRevision"].asInt())
        assertEquals(2, snapshot()["events"].size())
    }

    @Test
    fun `submicrosecond timestamps preserve exact retry and distinguish changed payload`() {
        val pull = event("PULL", time = at.plusNanos(123456789))
        val body = batch(listOf(pull))
        val first = send(body)
        val retry = send(body, 200)
        assertEquals(first["events"], retry["events"])
        val changed = pull + ("occurredAt" to at.plusNanos(123456788).toString())
        val problem = send(batch(listOf(changed)), 409)
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT", problem["code"].asText())
    }

    @Test
    fun `v1 shared pipeline also replays high precision timestamps consistently`() {
        val pull = event("PULL", time = at.plusNanos(123456789))
        val uri = "/api/v1/matches/${match.id}/events"
        val created = mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(pull)))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        mvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(pull)))
            .andExpect(status().isOk).andExpect(content().json(created))
        assertEquals(1, snapshot()["logRevision"].asInt())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = [123456123, 123456500, 123456789, 999999789])
    fun `equal original nanoseconds work across committed batches without accepting earlier time`(nanos: Long) {
        val original = at.plusNanos(nanos)
        val pull = event("PULL", time = original)
        val first = send(batch(listOf(pull)))
        assertEquals(original.toString(), first["events"][0]["occurredAt"].asText())
        val earlier = send(batch(listOf(event("PICKUP", time = original.minusNanos(1))), 1, 1), 409)
        assertEquals("EVENT_OUT_OF_ORDER", earlier["code"].asText())
        val pickup = event("PICKUP", time = original)
        val second = send(batch(listOf(pickup), 1, 1))
        assertEquals(original.toString(), second["events"][0]["occurredAt"].asText())
        val log = snapshot()
        assertEquals(listOf(1, 2), log["events"].map { it["sequenceNumber"].asInt() })
        assertEquals(listOf(original.toString(), original.toString()), log["events"].map { it["occurredAt"].asText() })
        assertEquals(first["events"], send(batch(listOf(pull)), 200)["events"])
    }

    @Test
    fun `v1 and v2 share exact nanosecond ordering across requests`() {
        val original = at.plusNanos(123456123)
        val pull = event("PULL", time = original)
        mvc.perform(post("/api/v1/matches/${match.id}/events").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(pull))).andExpect(status().isCreated)
        val earlier = send(batch(listOf(event("PICKUP", time = original.minusNanos(1))), 1, 1), 409)
        assertEquals("EVENT_OUT_OF_ORDER", earlier["code"].asText())
        send(batch(listOf(event("PICKUP", time = original.plusNanos(1))), 1, 1))
        assertEquals(original.plusNanos(1).toString(), snapshot()["events"][1]["occurredAt"].asText())
    }

    @Test
    fun `patch preserves original time and invalidates retry while equal append still works`() {
        val original = at.plusNanos(999999789)
        val pull = event("PULL", time = original)
        val body = batch(listOf(pull))
        send(body)
        fun patchParticipant(id: UUID) {
            mvc.perform(patch("/api/v1/matches/${match.id}/events/${pull["eventId"]}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapOf("type" to "PULL", "participantId" to id))))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.occurredAt").value(original.toString()))
        }
        patchParticipant(participant) // no-op PATCH still invalidates immutable retry binding
        send(body, 409)
        val teammate = matches.getOrThrow(match.id).participantsByTeam.values.first()[1].participantId
        patchParticipant(teammate)
        assertEquals(original.toString(), snapshot()["events"][0]["occurredAt"].asText())
        send(batch(listOf(event("PICKUP", time = original)), 1, 3))
        assertEquals(4, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `finish uses original event time and rejects one nanosecond earlier`() {
        val original = at.plusNanos(123456123)
        val teammate = matches.getOrThrow(match.id).participantsByTeam.values.first()[1].participantId
        val goal = mapOf("eventId" to UUID.randomUUID(), "type" to "GOAL", "occurredAt" to original.toString(),
            "fromParticipantId" to participant, "toParticipantId" to teammate)
        send(batch(listOf(event("PULL", time = original), event("PICKUP", time = original), goal)))
        fun finish(time: Instant) = mvc.perform(post("/api/v1/matches/${match.id}/end")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("timestamp" to time.toString()))))
        finish(original.minusNanos(1)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("END_BEFORE_LAST_EVENT"))
        finish(original).andExpect(status().isOk)
        assertEquals(original.toString(), snapshot()["events"].last()["occurredAt"].asText())
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = [123456123, 123456500, 123456789, 999999789])
    fun `start and finish retain exact boundaries through reload and portable round trip`(nanos: Long) {
        val started = at.plusNanos(nanos)
        val ended = started.plusSeconds(1)
        val other = Match(UUID.randomUUID(), match.teamIds)
        matches.create(other)
        val root = "/api/v1/matches/${other.id}"
        fun timestamp(command: String, time: Instant) = mvc.perform(post("$root/$command")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("timestamp" to time.toString()))))
        timestamp("start", started).andExpect(status().isOk)
        mvc.perform(get(root)).andExpect(status().isOk).andExpect(jsonPath("$.startedAt").value(started.toString()))
        val players = matches.getOrThrow(other.id).participantsByTeam.values.first()
        val who = players[0].participantId
        fun one(type: String, time: Instant) = mapOf("eventId" to UUID.randomUUID(), "type" to type,
            "occurredAt" to time.toString(), "participantId" to who)
        val batchPath = "/api/v2/matches/${other.id}/events/batch"
        mvc.perform(post(batchPath).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(batch(listOf(one("PULL", started.minusNanos(1)))))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("EVENT_BEFORE_START"))
        val goal = mapOf("eventId" to UUID.randomUUID(), "type" to "GOAL", "occurredAt" to ended.toString(),
            "fromParticipantId" to who, "toParticipantId" to players[1].participantId)
        mvc.perform(post(batchPath).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(batch(listOf(one("PULL", started), one("PICKUP", started), goal)))))
            .andExpect(status().isCreated)
        timestamp("end", ended.minusNanos(1)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("END_BEFORE_LAST_EVENT"))
        timestamp("end", ended).andExpect(status().isOk)
        mvc.perform(get(root)).andExpect(status().isOk).andExpect(jsonPath("$.endedAt").value(ended.toString()))
        fun portable(id: UUID) = mapper.readTree(mvc.perform(post("/api/v1/matches/export")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(mapOf("matchIds" to listOf(id)))))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val exported = portable(other.id)
        assertEquals(started.toString(), exported["matches"][0]["startedAt"].asText())
        assertEquals(ended.toString(), exported["matches"][0]["endedAt"].asText())
        val imported = mapper.readTree(mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(exported))).andExpect(status().isOk).andReturn().response.contentAsString)
        val copy = portable(UUID.fromString(imported["matchIds"][other.id.toString()].asText()))["matches"][0]
        assertEquals(started.toString(), copy["startedAt"].asText())
        assertEquals(ended.toString(), copy["endedAt"].asText())
        assertEquals(listOf(started.toString(), started.toString(), ended.toString()), copy["events"].map { it["occurredAt"].asText() })
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["2026-07-28T12:00:00Z", "1969-12-31T23:59:49Z"])
    fun `SQL pagination orders effective start and end precision before limit with legacy and stale fallback`(origin: String) {
        val teamIds = List(2) { com.github.mihanizzm.ultistats.model.Team(UUID.randomUUID(), "Precision list") }
            .onEach { teams.create(it) }.map { it.id }
        val base = Instant.parse(origin)
        val suffix = UUID.randomUUID().leastSignificantBits
        val a = Match(UUID(0L, suffix), teamIds, startedAt = base.plusNanos(123456123), endedAt = base.plusSeconds(10).plusNanos(123456123))
        val tie = a.copy(id = UUID(Long.MIN_VALUE, suffix)) // signed UUID tie-break stays ascending in either direction
        val b = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(123456124), endedAt = base.plusSeconds(10).plusNanos(123456124))
        val legacy = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(123456000), endedAt = base.plusSeconds(10).plusNanos(123456000),
            originalStartedAtSecond = null, originalStartedAtNano = null, originalEndedAtSecond = null, originalEndedAtNano = null)
        val stale = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(123457000), endedAt = base.plusSeconds(10).plusNanos(123457000),
            originalStartedAtSecond = base.epochSecond + 5, originalStartedAtNano = 0,
            originalEndedAtSecond = base.epochSecond + 15, originalEndedAtNano = 0)
        val invalid = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(123458000), endedAt = base.plusSeconds(10).plusNanos(123458000),
            originalStartedAtNano = 1000000000, originalEndedAtNano = -1)
        val partial = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(123459000), endedAt = base.plusSeconds(10).plusNanos(123459000),
            originalStartedAtSecond = null, originalEndedAtNano = null)
        val halfCarry = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(999999500), endedAt = base.plusSeconds(10).plusNanos(999999500))
        val carry = Match(UUID.randomUUID(), teamIds, startedAt = base.plusNanos(999999789), endedAt = base.plusSeconds(10).plusNanos(999999789))
        val next = Match(UUID.randomUUID(), teamIds, startedAt = base.plusSeconds(1), endedAt = base.plusSeconds(11))
        val planned = Match(UUID.randomUUID(), teamIds)
        listOf(a, tie, b, legacy, stale, invalid, partial, halfCarry, carry, next, planned).forEach { matches.create(it) }
        val ascending = listOf(legacy, tie, a, b, stale, invalid, partial, halfCarry, carry, next, planned)
        val descending = listOf(planned, next, carry, halfCarry, partial, invalid, stale, b, tie, a, legacy)
        for (field in listOf("startedAt", "endedAt")) {
            for ((direction, expected) in listOf("asc" to ascending, "desc" to descending)) {
                expected.forEachIndexed { page, match ->
                    val result = mapper.readTree(mvc.perform(get("/api/v1/matches")
                        .param("teamId", teamIds[0].toString()).param("sort", "$field:$direction")
                        .param("size", "1").param("page", page.toString())).andReturn().response.contentAsString)
                    assertEquals(11, result["totalElements"].asInt())
                    assertEquals(match.id.toString(), result["content"][0]["id"].asText(), "$field:$direction page$page")
                    val time = if (field == "startedAt") match.effectiveStartedAt else match.effectiveEndedAt
                    assertEquals(time?.toString(), result["content"][0][field]?.takeUnless { it.isNull }?.asText())
                }
            }
        }
    }

    @Test
    fun `stale base returns reconciliation metadata and does not append`() {
        send(batch(listOf(event("PULL"))))
        val result = send(batch(listOf(event("PICKUP"))), 409)
        assertEquals("EVENT_LOG_STALE", result["code"].asText())
        assertEquals(1, result["lastSequenceNumber"].asInt())
        assertEquals(1, result["logRevision"].asInt())
        assertEquals(1, snapshot()["events"].size())
    }

    @Test
    fun `invalid sequence rolls back whole batch and identifies offending event`() {
        val second = event("PULL")
        val problem = send(batch(listOf(event("PULL"), second)), 409)
        assertEquals("EVENT_SEQUENCE_VIOLATION", problem["code"].asText())
        assertEquals(second["eventId"].toString(), problem["eventId"].asText())
        assertEquals(1, problem["eventIndex"].asInt())
        assertEquals(0, snapshot()["events"].size())
        assertEquals(0, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `partial retry and changed payload cannot append new suffix`() {
        val pull = event("PULL")
        send(batch(listOf(pull)))
        send(batch(listOf(pull, event("PICKUP"))), 409)
        val changed = pull + ("occurredAt" to at.plusSeconds(1).toString())
        val problem = send(batch(listOf(changed)), 409)
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT", problem["code"].asText())
        assertEquals(1, snapshot()["events"].size())
    }

    @Test
    fun `exact retry survives append but not unrelated patch`() {
        val body = batch(listOf(event("PULL")))
        send(body)
        val pickup = event("PICKUP")
        send(batch(listOf(pickup), 1, 1))
        val replay = send(body, 200)
        assertEquals(2, replay["lastSequenceNumber"].asInt())
        assertEquals(2, replay["logRevision"].asInt())
        mvc.perform(patch("/api/v1/matches/${match.id}/events/${pickup["eventId"]}")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("type" to "PICKUP", "participantId" to participant))))
            .andExpect(status().isOk)
        assertEquals(3, snapshot()["logRevision"].asInt())
        send(body, 409)
        // Same sequence, old revision must still conflict for a completely new batch.
        send(batch(listOf(event("INCOMPLETE_PASS")), 2, 2), 409)
    }

    @Test
    fun `deletion preserves sequence high water mark and invalidates retry`() {
        val pull = event("PULL")
        val body = batch(listOf(pull))
        send(body)
        mvc.perform(delete("/api/v1/matches/${match.id}/events/${pull["eventId"]}"))
            .andExpect(status().isNoContent)
        assertEquals(1, snapshot()["lastSequenceNumber"].asInt())
        assertEquals(2, snapshot()["logRevision"].asInt())
        send(body, 409)
        val result = send(batch(listOf(event("PULL")), 1, 2))
        assertEquals(2, result["events"][0]["sequenceNumber"].asInt())
    }

    @Test
    fun `v1 append shares revisions and policy with v2`() {
        mvc.perform(post("/api/v1/matches/${match.id}/events")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(event("PULL"))))
            .andExpect(status().isCreated)
        assertEquals(1, snapshot()["logRevision"].asInt())
        send(batch(listOf(event("PULL")), 1, 1), 409)
        send(batch(listOf(event("PICKUP")), 1, 1))
        assertEquals(2, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `batch score is updated from all committed events and retry does not score twice`() {
        val teammate = matches.getOrThrow(match.id).participantsByTeam.values.first()[1].participantId
        val goal = mapOf("eventId" to UUID.randomUUID(), "type" to "GOAL", "occurredAt" to at.toString(),
            "fromParticipantId" to participant, "toParticipantId" to teammate)
        val body = batch(listOf(event("PULL"), event("PICKUP"), goal))
        send(body)
        fun score() = mapper.readTree(mvc.perform(get("/api/v1/matches/${match.id}"))
            .andExpect(status().isOk).andReturn().response.contentAsString)["teams"].sumOf { it["teamScore"].asInt() }
        assertEquals(1, score())
        send(body, 200)
        assertEquals(1, score())
        assertEquals(1, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `retry succeeds after match finishes while new append is rejected`() {
        val teammate = matches.getOrThrow(match.id).participantsByTeam.values.first()[1].participantId
        val goal = mapOf("eventId" to UUID.randomUUID(), "type" to "GOAL", "occurredAt" to at.toString(),
            "fromParticipantId" to participant, "toParticipantId" to teammate)
        val body = batch(listOf(event("PULL"), event("PICKUP"), goal))
        send(body)
        matches.endMatch(match.id, at.plusSeconds(1))
        send(body, 200)
        val problem = send(batch(listOf(event("PULL")), 3, 1), 409)
        assertEquals("MATCH_NOT_IN_PROGRESS", problem["code"].asText())
        assertEquals(1, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `cross match UUID collision rolls back the whole losing batch`() {
        val other = Match(UUID.randomUUID(), match.teamIds)
        matches.create(other)
        matches.startMatch(other.id, at)
        val otherParticipant = matches.getOrThrow(other.id).participantsByTeam.values.first().first().participantId
        val shared = UUID.randomUUID()
        val requests = listOf(match.id to participant, other.id to otherParticipant).map { (matchId, player) ->
            val prefix = listOf("TIMEOUT_START", "TIMEOUT_END").map { type -> mapOf(
                "eventId" to UUID.randomUUID(), "type" to type, "occurredAt" to at.toString(), "teamId" to match.teamIds.first(),
            ) }
            matchId to batch(prefix + (event("PULL", shared) + ("participantId" to player)))
        }
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val futures = requests.map { (matchId, body) -> executor.submit<Pair<UUID, org.springframework.mock.web.MockHttpServletResponse>> {
                check(gate.await(10, TimeUnit.SECONDS))
                matchId to mvc.perform(post("/api/v2/matches/$matchId/events/batch")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))).andReturn().response
            } }
            gate.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(listOf(201, 409), results.map { it.second.status }.sorted())
            val (loser, response) = results.single { it.second.status == 409 }
            val problem = mapper.readTree(response.contentAsString)
            assertEquals("EVENT_IDEMPOTENCY_CONFLICT", problem["code"].asText())
            assertEquals(shared.toString(), problem["eventId"].asText())
            assertEquals(2, problem["eventIndex"].asInt())
            assertEquals(0, problem["lastSequenceNumber"].asInt())
            val log = mapper.readTree(mvc.perform(get("/api/v2/matches/$loser/events"))
                .andExpect(status().isOk).andReturn().response.contentAsString)
            assertEquals(0, log["events"].size())
            assertEquals(0, log["logRevision"].asInt())
        }
    }

    @Test
    fun `bounds missing ids negative bases and duplicate ids are bad requests`() {
        val pull = event("PULL")
        listOf(
            batch(emptyList()), batch(List(101) { event("PULL") }), batch(listOf(pull, pull)),
            batch(listOf(pull - "eventId")), batch(listOf(pull), -1), batch(listOf(pull), revision = -1),
            mapOf("events" to listOf(pull), "expectedLastSequenceNumber" to 0),
            mapOf("events" to listOf(pull), "expectedLogRevision" to 0),
        ).forEach { send(it, 400) }
        assertEquals(0, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `null event element is a bad request without writes`() {
        send(mapOf("events" to listOf(null), "expectedLastSequenceNumber" to 0, "expectedLogRevision" to 0), 400)
        assertEquals(0, snapshot()["events"].size())
    }

    @Test
    fun `late invalid participant and backwards timestamp leave the entire log unchanged`() {
        val invalid = event("PICKUP") + ("participantId" to UUID.randomUUID())
        val problem = send(batch(listOf(event("PULL"), invalid)), 400)
        assertEquals(1, problem["eventIndex"].asInt())
        assertEquals(0, snapshot()["events"].size())
        val backwards = send(batch(listOf(event("PULL", time = at.plusSeconds(2)), event("PICKUP"))), 409)
        assertEquals("EVENT_OUT_OF_ORDER", backwards["code"].asText())
        assertEquals(0, snapshot()["events"].size())
    }

    @Test
    fun `maximum batch size is accepted and increments revision once`() {
        val events = List(100) { index -> mapOf(
            "eventId" to UUID.randomUUID(), "type" to if (index % 2 == 0) "TIMEOUT_START" else "TIMEOUT_END",
            "occurredAt" to at.toString(), "teamId" to match.teamIds.first(),
        ) }
        val result = send(batch(events))
        assertEquals(100, result["events"].size())
        assertEquals(100, result["lastSequenceNumber"].asInt())
        assertEquals(1, result["logRevision"].asInt())
    }

    @Test
    fun `OpenAPI exposes required batch bases limits and reconciliation metadata without deprecating v1`() {
        val api = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsString)
        val operation = api["paths"]["/api/v2/matches/{matchId}/events/batch"]["post"]
        assertEquals(setOf("200", "201", "400", "404", "409"), operation["responses"].fieldNames().asSequence().toSet())
        val schema = api["components"]["schemas"]["CreateEventBatchRequest"]
        assertEquals(setOf("events", "expectedLastSequenceNumber", "expectedLogRevision"), schema["required"].map { it.asText() }.toSet())
        assertEquals(1, schema["properties"]["events"]["minItems"].asInt())
        assertEquals(100, schema["properties"]["events"]["maxItems"].asInt())
        val item = schema["properties"]["events"]["items"]
        val itemSchema = api["components"]["schemas"][item["\$ref"].asText().substringAfterLast('/')]
        assertEquals(listOf("eventId"), itemSchema["required"].map { it.asText() })
        val error = api["components"]["schemas"]["EventBatchProblem"]
        listOf("code", "lastSequenceNumber", "logRevision", "eventId", "eventIndex").forEach {
            assertEquals(true, error["properties"].has(it))
        }
        assertEquals(false, api["paths"]["/api/v1/matches/{matchId}/events"]["post"].path("deprecated").asBoolean())
    }

    @Test
    fun `reordered and subset retries are conflicts`() {
        val pull = event("PULL")
        val pickup = event("PICKUP")
        send(batch(listOf(pull, pickup)))
        send(batch(listOf(pickup, pull)), 409)
        send(batch(listOf(pull)), 409)
        assertEquals(1, snapshot()["logRevision"].asInt())
    }

    @Test
    fun `same base concurrent requests serialize and identical retries converge`() {
        fun race(bodies: List<Any>): List<Int> {
            val executor = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            try {
                val futures = bodies.map { body -> executor.submit<Int> {
                    gate.await(10, TimeUnit.SECONDS)
                    mvc.perform(post("$path/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body))).andReturn().response.status
                } }
                gate.countDown()
                return futures.map { it.get(20, TimeUnit.SECONDS) }.sorted()
            } finally { executor.shutdownNow() }
        }
        val body = batch(listOf(event("PULL")))
        assertEquals(listOf(200, 201), race(listOf(body, body)))
        assertEquals(listOf(201, 409), race(listOf(
            batch(listOf(event("PICKUP")), 1, 1), batch(listOf(event("PICKUP")), 1, 1),
        )))
        assertEquals(2, snapshot()["events"].size())
        assertEquals(2, snapshot()["logRevision"].asInt())
    }
}
