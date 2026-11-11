package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class AnnouncementHttpFixture {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var matches: MatchService
    @Autowired lateinit var teams: TeamService
    protected lateinit var match: Match
    protected val at = Instant.parse("2026-07-28T12:00:00Z")
    protected val route get() = "/api/v1/matches/${match.id}/announcements"
    protected val eventRoute get() = "/api/v1/matches/${match.id}/events"

    @BeforeEach
    fun createMatch() {
        val teamIds = listOf("One", "Two").map { Team(UUID.randomUUID(), it).also(teams::create).id }
        match = Match(UUID.randomUUID(), teamIds)
        matches.create(match)
        matches.startMatch(match.id, at)
    }

    protected fun completePoint() {
        val roster = matches.getOrThrow(match.id).participantsByTeam
        val puller = roster.getValue(match.teamIds[0])[0].participantId
        val attack = roster.getValue(match.teamIds[1])
        event("PULL", at.plusSeconds(1), mapOf("participantId" to puller))
        event("PICKUP", at.plusSeconds(2), mapOf("participantId" to attack[0].participantId))
        event("GOAL", at.plusSeconds(3), mapOf("fromParticipantId" to attack[0].participantId, "toParticipantId" to attack[1].participantId))
    }

    protected fun event(type: String, time: Instant, fields: Map<String, UUID>) =
        command(eventRoute, fields + mapOf("type" to type, "occurredAt" to time), 201)

    protected fun command(path: String, body: Any?, code: Int): JsonNode {
        val request = post(path)
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body))
        val response = mvc.perform(request).andExpect(status().`is`(code)).andReturn().response
        if (code == 409 && "/announcements" in path) {
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.contentType)
        }
        return mapper.readTree(response.contentAsByteArray)
    }

    protected fun correct(id: UUID, body: Map<String, Any?>, code: Int): JsonNode {
        val payload = mapper.valueToTree<ObjectNode>(body)
        body.filterValues { it == null }.keys.forEach(payload::putNull)
        val response = mvc.perform(patch("$route/$id/team").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(payload))).andExpect(status().`is`(code)).andReturn().response
        if (code != 200) assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.contentType)
        return mapper.readTree(response.contentAsByteArray)
    }

    protected fun list(): JsonNode = mapper.readTree(
        mvc.perform(get(route)).andExpect(status().isOk).andReturn().response.contentAsByteArray,
    )
}

@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = ["ultistats.announcements.write-enabled=true"])
open class AnnouncementControllerTest : AnnouncementHttpFixture() {
    @Test
    fun `standalone interval preserves nanoseconds and match-scoped retries`() {
        val id = UUID.randomUUID()
        val body = mapOf("id" to id, "teamId" to null, "startedAt" to "2026-07-28T12:00:01.123456789Z")
        mvc.perform(get(route)).andExpect(status().isOk)
            .andExpect(header().string(ANNOUNCEMENT_START_ENABLED_HEADER, "true"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        assertEquals(0, list().size())
        val opened = command(route, body, 201)
        assertEquals(id.toString(), opened["id"].asText())
        assertTrue(opened["teamId"].isNull)
        assertTrue(opened["endedAt"].isNull)
        assertEquals("2026-07-28T12:00:01.123456789Z", opened["startedAt"].asText())
        assertEquals(opened, command(route, body - "teamId", 200))
        assertEquals("ANNOUNCEMENT_CONFLICT", command(route, body + ("teamId" to match.teamIds.first()), 409)["code"].asText())
        assertEquals("ANNOUNCEMENT_ALREADY_OPEN", command(route, body + ("id" to UUID.randomUUID()), 409)["code"].asText())
        val end = mapOf("endedAt" to "2026-07-28T12:00:02.987654321Z")
        val closed = command("$route/$id/end", end, 200)
        assertEquals("2026-07-28T12:00:02.987654321Z", closed["endedAt"].asText())
        assertEquals(closed, command("$route/$id/end", end, 200))
        assertEquals("ANNOUNCEMENT_CONFLICT", command("$route/$id/end", mapOf("endedAt" to at.plusSeconds(4)), 409)["code"].asText())
        assertEquals(closed, command(route, body, 200))
        assertEquals(closed, list().single())
        createMatch()
        assertEquals(201, mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(body))).andReturn().response.status)
    }

    @Test
    fun `validates bounds and team and sorts equal starts by ID`() {
        val high = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val low = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val start = mapOf("id" to high, "startedAt" to at.plusSeconds(1))
        assertEquals("INVALID_REQUEST", command(route, start + ("teamId" to UUID.randomUUID()), 400)["code"].asText())
        assertEquals("INVALID_REQUEST", command(route, start + ("startedAt" to at.minusNanos(1)), 400)["code"].asText())
        assertEquals("INVALID_REQUEST", command(route, start + ("startedAt" to "invalid"), 400)["code"].asText())
        assertEquals("INVALID_REQUEST", command(route, start + ("startedAt" to at.epochSecond), 400)["code"].asText())
        command(route, start, 201)
        command("$route/$low/end", mapOf("endedAt" to at.plusSeconds(1)), 404)
        assertEquals("INVALID_REQUEST",
            command("$route/$high/end", mapOf("endedAt" to at), 400)["code"].asText())
        command("$route/$high/end", mapOf("endedAt" to at.plusSeconds(1)), 200)
        assertEquals("INVALID_REQUEST", command(route, mapOf("id" to low, "startedAt" to at), 400)["code"].asText())
        command(route, start + ("id" to low), 201)
        assertEquals(listOf(low, high).map(UUID::toString), list().map { it["id"].asText() })
        mvc.perform(get("/api/v1/matches/${UUID.randomUUID()}/announcements")).andExpect(status().isNotFound)
    }

    @Test
    fun `undo affects gameplay but leaves the open interval intact`() {
        val id = UUID.randomUUID()
        val baseline = log()
        val opened = command(route, mapOf("id" to id, "startedAt" to at, "teamId" to match.teamIds.first()), 201)
        assertEquals(baseline, log())
        val player = matches.getOrThrow(match.id).participantsByTeam.values.first().first().participantId
        event("PULL", at.plusSeconds(1), mapOf("participantId" to player))
        command("$eventRoute/undo", null, 200)
        val afterUndo = log()
        assertEquals(0, afterUndo["events"].size())
        assertEquals(opened, list().single())
        command("$route/$id/end", mapOf("endedAt" to at.plusSeconds(2)), 200)
        assertEquals(afterUndo, log())
    }

    @Test
    fun `only a valid match finish closes its open interval`() {
        val id = UUID.randomUUID()
        val body = mapOf("id" to id, "startedAt" to at.plusSeconds(10))
        command(route, body, 201)
        val finish = "/api/v1/matches/${match.id}/end"
        command(finish, mapOf("timestamp" to at.plusSeconds(20)), 409)
        assertTrue(list().single()["endedAt"].isNull)
        completePoint()
        command(finish, mapOf("timestamp" to at.plusSeconds(9)), 409)
        assertTrue(list().single()["endedAt"].isNull)
        val endedAt = "2026-07-28T12:00:20.987654321Z"
        val detail = command(finish, mapOf("timestamp" to endedAt), 200)
        assertEquals("FINISHED", detail["status"].asText())
        val closed = list().single()
        assertEquals(endedAt, closed["endedAt"].asText())
        assertEquals(closed, command(route, body, 200))
        assertEquals(closed, command("$route/$id/end", mapOf("endedAt" to endedAt), 200))
    }

    @Test
    fun `revision prevents stale writes even when attribution returns to unknown`() {
        completePoint()
        val id = UUID.randomUUID()
        val start = mapOf("id" to id, "teamId" to null, "startedAt" to at.plusSeconds(1))
        val end = mapOf("endedAt" to at.plusSeconds(2))
        command(route, start, 201)
        val initial = command("$route/$id/end", end, 200)
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(4)), 200)

        val first = correct(id, mapOf("teamId" to match.teamIds[0], "expectedAttributionRevision" to 0), 200)
        assertEquals(0, initial["attributionRevision"].asInt())
        assertEquals(match.teamIds[0].toString(), first["teamId"].asText())
        assertEquals(1, first["attributionRevision"].asInt())
        assertEquals(first, correct(id, mapOf("teamId" to match.teamIds[0], "expectedAttributionRevision" to 1), 200))
        assertEquals(first, command(route, start, 200))
        assertEquals(first, command("$route/$id/end", end, 200))
        command(route, start + ("teamId" to match.teamIds[0]), 409)

        val second = correct(id, mapOf("teamId" to match.teamIds[1], "expectedAttributionRevision" to 1), 200)
        assertEquals(match.teamIds[1].toString(), second["teamId"].asText())
        assertEquals(2, second["attributionRevision"].asInt())
        val unknown = mapOf("teamId" to null, "expectedAttributionRevision" to 2)
        val third = correct(id, unknown, 200)
        assertTrue(third["teamId"].isNull)
        assertEquals(3, third["attributionRevision"].asInt())
        assertEquals(initial["startedAt"], third["startedAt"])
        assertEquals(initial["endedAt"], third["endedAt"])
        val stale = correct(id, unknown + ("expectedAttributionRevision" to 0), 409)
        assertEquals("ANNOUNCEMENT_ATTRIBUTION_STALE", stale["code"].asText())
        assertEquals(third, list().single())
    }

    @Test
    fun `rejects wrong target match state or correction payload`() {
        val id = UUID.randomUUID()
        val start = mapOf("id" to id, "startedAt" to at, "teamId" to match.teamIds[0])
        val body = mapOf("teamId" to null, "expectedAttributionRevision" to 0)
        command(route, start, 201)
        assertEquals("ANNOUNCEMENT_NOT_CLOSED", correct(id, body, 409)["code"].asText())
        command("$route/$id/end", mapOf("endedAt" to at.plusSeconds(1)), 200)
        assertEquals("MATCH_NOT_FINISHED", correct(id, body, 409)["code"].asText())
        completePoint()
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(4)), 200)
        assertEquals("RESOURCE_NOT_FOUND", correct(UUID.randomUUID(), body, 404)["code"].asText())
        val invalid = listOf(body - "teamId", body - "expectedAttributionRevision", body + ("teamId" to UUID.randomUUID()),
            body + ("teamId" to ""), body + ("teamId" to 1), body + ("expectedAttributionRevision" to null),
            body + ("expectedAttributionRevision" to 0.5), body + ("expectedAttributionRevision" to "0"),
            body + ("expectedAttributionRevision" to -1))
        invalid.forEach { assertEquals("INVALID_REQUEST", correct(id, it, 400)["code"].asText()) }
        val corrected = correct(id, body, 200)
        assertEquals(corrected, command(route, start, 200))
        createMatch()
        assertEquals("RESOURCE_NOT_FOUND", correct(id, body, 404)["code"].asText())
    }

    private fun log(): JsonNode = mapper.readTree(
        mvc.perform(get("/api/v2/matches/${match.id}/events"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,
    )
}

@ActiveProfiles("postgres-list")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AnnouncementPostgresTest : AnnouncementControllerTest() {
    @Test
    fun `competing corrections at the same revision accept exactly one`() {
        completePoint()
        val id = UUID.randomUUID()
        command(route, mapOf("id" to id, "startedAt" to at), 201)
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(4)), 200)
        val gate = CountDownLatch(1)
        val statuses = Executors.newFixedThreadPool(2).use { executor ->
            val attempts = match.teamIds.map { teamId ->
                executor.submit<Int> {
                    val body = mapOf("teamId" to teamId, "expectedAttributionRevision" to 0)
                    check(gate.await(10, TimeUnit.SECONDS))
                    mvc.perform(patch("$route/$id/team").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(body))).andReturn().response.status
                }
            }
            gate.countDown()
            attempts.map { it.get(20, TimeUnit.SECONDS) }
        }
        assertEquals(listOf(200, 409), statuses.sorted())
        assertEquals(1, list().single()["attributionRevision"].asInt())
    }

    @Test
    fun `competing starts serialize to one open interval`() {
        val gate = CountDownLatch(1)
        val statuses = Executors.newFixedThreadPool(2).use { executor ->
            val attempts = (1..2).map {
                executor.submit<Int> {
                    val body = mapper.writeValueAsBytes(mapOf("id" to UUID.randomUUID(), "startedAt" to "2026-07-28T12:00:01Z"))
                    check(gate.await(10, TimeUnit.SECONDS))
                    mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response.status
                }
            }
            gate.countDown()
            attempts.map { it.get(20, TimeUnit.SECONDS) }
        }
        assertEquals(listOf(201, 409), statuses.sorted())
        assertEquals(1, list().size())
        assertTrue(list().single()["endedAt"].isNull)
    }
}


@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
@SpringBootTest
open class AnnouncementWriteDisabledTest : AnnouncementHttpFixture() {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var context: WebApplicationContext

    @Test
    fun `default is read only for new starts but preserves retries and END`() {
        val id = UUID.randomUUID()
        seedOpen(id)
        val rawMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        rawMvc.perform(get(route))
            .andExpect(status().isOk)
            .andExpect(header().string(ANNOUNCEMENT_START_ENABLED_HEADER, "false"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        val approvedOrigin = "https://localhost:8443"
        rawMvc.perform(get(route).header(HttpHeaders.ORIGIN, approvedOrigin))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, approvedOrigin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                "$ANNOUNCEMENT_START_ENABLED_HEADER, $ANNOUNCEMENT_STATISTICS_HEADER, $POSSESSION_CHANGE_ENABLED_HEADER"))
            .andExpect(header().string(ANNOUNCEMENT_START_ENABLED_HEADER, "false"))

        val body = mapOf("id" to id, "startedAt" to at)
        assertEquals(list().single(), command(route, body, 200))
        val rejected = command(route, body + ("id" to UUID.randomUUID()), 409)
        assertEquals("ANNOUNCEMENT_WRITE_DISABLED", rejected["code"].asText())
        val closed = command("$route/$id/end", mapOf("endedAt" to at.plusSeconds(1)), 200)
        assertEquals(closed, command(route, body, 200))

        // In-progress statistics require staff credentials. Finished statistics are public.
        completePoint()
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(4)), 200)
        val statsRoute = "/api/v1/matches/${match.id}/statistics"
        rawMvc.perform(get(statsRoute).header(HttpHeaders.ORIGIN, approvedOrigin))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, approvedOrigin))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                "$ANNOUNCEMENT_START_ENABLED_HEADER, $ANNOUNCEMENT_STATISTICS_HEADER, $POSSESSION_CHANGE_ENABLED_HEADER"))
            .andExpect(header().string(ANNOUNCEMENT_STATISTICS_HEADER, "combined-v1"))
    }

    @Test
    fun `finish still closes an open interval with new starts disabled`() {
        val id = UUID.randomUUID()
        seedOpen(id)
        completePoint()
        val endedAt = "2026-07-28T12:00:20.987654321Z"
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to endedAt), 200)
        assertEquals(endedAt, list().single()["endedAt"].asText())
    }

    @Test
    fun `portable import cannot create intervals with announcement writes disabled`() {
        val selection = mapOf("matchIds" to listOf(match.id))
        val versionOne = command("/api/v1/matches/export", selection, 200)
        assertEquals(1, versionOne["formatVersion"].asInt())
        command("/api/v1/matches/import", versionOne, 200)

        seedOpen(UUID.randomUUID())
        val versionTwo = command("/api/v1/matches/export", selection, 200)
        assertEquals(2, versionTwo["formatVersion"].asInt())
        assertEquals(1, versionTwo["matches"][0]["announcements"].size())
        val before = matches.count()
        val rejection = command("/api/v1/matches/import", versionTwo, 409)
        assertEquals("ANNOUNCEMENT_WRITE_DISABLED", rejection["code"].asText())
        assertEquals(before, matches.count())
    }

    @Test
    fun `disabled writes reject attribution correction for a finished match`() {
        val id = UUID.randomUUID()
        seedOpen(id)
        completePoint()
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(4)), 200)
        val baseline = list()
        val rejected = correct(id, mapOf("teamId" to match.teamIds[0], "expectedAttributionRevision" to 0), 409)
        assertEquals("ANNOUNCEMENT_WRITE_DISABLED", rejected["code"].asText())
        assertEquals(baseline, list())
    }

    private fun seedOpen(id: UUID) {
        jdbc.update(
            "INSERT INTO match_announcements (match_id, id, started_second, started_nano) VALUES (?, ?, ?, ?)",
            match.id, id, at.epochSecond, at.nano,
        )
    }
}


@ActiveProfiles("postgres-list")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AnnouncementWriteDisabledPostgresTest : AnnouncementWriteDisabledTest()
