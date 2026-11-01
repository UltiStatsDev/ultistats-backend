package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.model.Match
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Run only against an isolated PostgreSQL database; includes all common batch contract tests. */
@ActiveProfiles("postgres-list")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class EventBatchPostgresTest : EventBatchControllerTest() {
    @Autowired lateinit var jdbc: JdbcTemplate
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var streams: com.github.mihanizzm.ultistats.realtime.MatchEventStreamService

    @Test
    fun `crossed UUID batches retain request order and return typed conflict without a deadlock`() {
        // A real PG trigger widens the interval after each unique-key acquisition. With
        // request-ordered insertion, opposite first keys form the reproduced 40P01 cycle.
        // Canonical acquisition instead makes the second transaction wait at its first key.
        jdbc.execute("""
            CREATE FUNCTION batch71_pause_insert() RETURNS trigger LANGUAGE plpgsql AS '
            BEGIN PERFORM pg_sleep(0.15); RETURN NEW; END';
            CREATE TRIGGER batch71_pause AFTER INSERT ON events
            FOR EACH ROW EXECUTE FUNCTION batch71_pause_insert();
        """.trimIndent())
        try {
            repeat(6) {
                val at = Instant.parse("2026-07-28T12:00:00Z")
                val teamIds = List(2) { com.github.mihanizzm.ultistats.model.Team(UUID.randomUUID(), "Crossed UUID team") }
                    .onEach { teams.create(it) }.map { it.id }
                val pair = List(2) { Match(UUID.randomUUID(), teamIds) }
                pair.forEach { matches.create(it); matches.startMatch(it.id, at) }
                val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
                val bodies = List(2) { side ->
                    val order = if (side == 0) ids else ids.reversed()
                    mapOf("expectedLastSequenceNumber" to 0, "expectedLogRevision" to 0,
                        "events" to listOf("TIMEOUT_START", "TIMEOUT_END").mapIndexed { index, type ->
                            mapOf("eventId" to order[index], "type" to type, "occurredAt" to at.toString(), "teamId" to teamIds[0])
                        })
                }
                clearInvocations(matches, streams)
                val gate = CountDownLatch(1)
                val results = Executors.newFixedThreadPool(2).use { executor ->
                    val futures = pair.mapIndexed { side, match -> executor.submit<org.springframework.mock.web.MockHttpServletResponse> {
                        check(gate.await(10, TimeUnit.SECONDS))
                        mvc.perform(post("/api/v2/matches/${match.id}/events/batch")
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(bodies[side])))
                            .andReturn().response
                    } }
                    gate.countDown()
                    futures.map { it.get(20, TimeUnit.SECONDS) }
                }
                assertEquals(listOf(201, 409), results.map { it.status }.sorted())
                pair.forEachIndexed { side, match ->
                    val response = results[side]
                    val log = mapper.readTree(mvc.perform(get("/api/v2/matches/${match.id}/events"))
                        .andReturn().response.contentAsString)
                    val winner = response.status == 201
                    assertEquals(if (winner) 2 else 0, log["events"].size())
                    assertEquals(if (winner) 1 else 0, log["logRevision"].asInt())
                    assertEquals(if (winner) 2 else 0, log["lastSequenceNumber"].asInt())
                    assertEquals(if (winner) 1 else 0, jdbc.queryForObject(
                        "SELECT count(*) FROM event_batch_receipts WHERE match_id = ?", Int::class.java, match.id))
                    if (winner) {
                        val replay = mvc.perform(post("/api/v2/matches/${match.id}/events/batch")
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(bodies[side])))
                            .andReturn().response
                        assertEquals(200, replay.status)
                        assertEquals(mapper.readTree(response.contentAsString), mapper.readTree(replay.contentAsString))
                    }
                    verify(matches, times(if (winner) 1 else 0)).recalculateScore(match.id)
                    val notifications = org.mockito.Mockito.mockingDetails(streams).invocations.filter {
                        it.method.name == "eventLogChanged" && it.arguments[0] == match.id
                    }
                    assertEquals(if (winner) 1 else 0, notifications.size)
                    if (winner) assertEquals((if (side == 0) ids else ids.reversed()).last(), notifications.single().arguments[2])
                    assertTrue(matches.getOrThrow(match.id).teamScores.all { it.score == 0 })
                    if (winner) {
                        val order = if (side == 0) ids else ids.reversed()
                        assertEquals(order.map(UUID::toString), log["events"].map { it["id"].asText() })
                        assertEquals(listOf(1, 2), log["events"].map { it["sequenceNumber"].asInt() })
                    } else {
                        val problem = mapper.readTree(response.contentAsString)
                        assertEquals("EVENT_IDEMPOTENCY_CONFLICT", problem["code"].asText())
                        assertEquals(0, problem["lastSequenceNumber"].asInt())
                        assertEquals(0, problem["logRevision"].asInt())
                        assertTrue(problem["eventId"].asText() in ids.map(UUID::toString))
                        val requestOrder = if (side == 0) ids else ids.reversed()
                        assertEquals(requestOrder.indexOf(UUID.fromString(problem["eventId"].asText())), problem["eventIndex"].asInt())
                        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM events WHERE match_id = ?", Int::class.java, match.id))
                    }
                }
            }
        } finally {
            jdbc.execute("DROP TRIGGER batch71_pause ON events; DROP FUNCTION batch71_pause_insert()")
        }
    }
}
