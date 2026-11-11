package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "ultistats.possession-change.write-enabled=true",
    "ultistats.announcements.write-enabled=true",
])
class PossessionChangeControllerTest : PossessionChangeHttpFixture() {
    @Test
    fun `a neutral change crosses teams during a standalone call and splits only active time`() {
        val first = side(1)[0]
        val second = side(0)[0]
        pull()
        event("PICKUP", at, mapOf("participantId" to first))
        val baseline = stats()
        val beforeScore = matchScore()
        val callId = UUID.randomUUID()
        val opened = command(route, mapOf("id" to callId, "startedAt" to at.plusSeconds(6)), 201)

        val base = journal("true")
        val neutral = set(second, seconds = 10)
        val request = batch(base, neutral)
        val applied = command(batchRoute, request, 201)
        val record = applied["events"].single()
        assertEquals("POSSESSION_SET", record["type"].asText())
        assertEquals(second.toString(), record["participantId"].asText())
        assertEquals(neutral["eventId"].toString(), record["id"].asText())
        assertEquals(at.plusSeconds(10).toString(), record["occurredAt"].asText())
        assertEquals(base["logRevision"].asLong() + 1, applied["logRevision"].asLong())
        assertEquals(applied, command(batchRoute, request, 200))
        val conflict = command(batchRoute, batch(base, neutral + ("participantId" to first)), 409)
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT", conflict["code"].asText())
        assertEquals(record, journal()["events"].last())
        assertEquals(record, read(eventRoute).last())
        assertEquals(record, read("$eventRoute/${record["id"].asText()}"))
        assertEquals(opened, list().single())
        assertCounters(baseline, stats())
        assertEquals(beforeScore, matchScore())
        assertEquals("EVENT_SEQUENCE_VIOLATION", command(
            batchRoute, batch(journal(), set(first, 11) + ("type" to "PICKUP")), 409,
        )["code"].asText())

        command("$route/$callId/end", mapOf("endedAt" to at.plusSeconds(14)), 200)
        event("GOAL", at.plusSeconds(18), mapOf("fromParticipantId" to second, "toParticipantId" to side(0)[1]))
        val completed = stats()
        assertEquals(18_000, completed["time"]["totalTimeMs"].asInt())
        assertEquals(10_000, completed["time"]["pureGameTimeMs"].asInt())
        assertEquals(8_000, completed["announcements"]["durationMs"].asInt())
        assertHold(completed, 1, first, 6_000)
        assertHold(completed, 0, second, 4_000)
        assertEquals(1, completed["teams"][0]["attack"]["score"].asInt())
    }

    @Test
    fun `setting the current holder leaves one ten second completed hold`() {
        val holder = side(1)[0]
        pull()
        event("PICKUP", at, mapOf("participantId" to holder))
        val callId = UUID.randomUUID()
        command(route, mapOf("id" to callId, "startedAt" to at.plusSeconds(6)), 201)
        command(batchRoute, batch(journal(), set(holder, 10)), 201)
        command("$route/$callId/end", mapOf("endedAt" to at.plusSeconds(14)), 200)
        event("GOAL", at.plusSeconds(18), mapOf("fromParticipantId" to holder, "toParticipantId" to side(1)[1]))
        assertHold(stats(), 1, holder, 10_000)
    }

    @Test
    fun `only a roster member can take a loose disc during an unblocked point`() {
        val member = side(0)[0]
        val fresh = journal()
        assertEquals("EVENT_SEQUENCE_VIOLATION", command(batchRoute, batch(fresh, set(member, 0)), 409)["code"].asText())
        pull()
        val loose = journal()
        for (invalid in listOf(set(UUID.randomUUID(), 1), set(member, 1) - "participantId",
            set(member, 1) - "participantId" + ("teamId" to match.teamIds[0]), set(member, 1) - "occurredAt")) {
            assertEquals("INVALID_REQUEST", command(batchRoute, batch(loose, invalid), 400)["code"].asText())
        }
        val blocked = listOf("ANNOUNCEMENT_START" to "ANNOUNCEMENT_END", "TIMEOUT_START" to "TIMEOUT_END")
        event("BRICK", at, mapOf("participantId" to member))
        for ((start, end) in blocked) {
            if (start == "TIMEOUT_START") event("PICKUP", at, mapOf("participantId" to member))
            val fields = if (start == "TIMEOUT_START") mapOf("teamId" to match.teamIds[0]) else emptyMap()
            event(start, at, fields)
            assertEquals("EVENT_SEQUENCE_VIOLATION", command(batchRoute, batch(journal(), set(member, 1)), 409)["code"].asText())
            event(end, at, fields)
        }
        event("INCOMPLETE_PASS", at, mapOf("participantId" to member))
        val baseline = stats()
        val apply = command(batchRoute, batch(journal(), set(side(1)[0], 1)), 201)
        assertCounters(baseline, stats())
        val undone = undo(apply)
        assertEquals("APPLIED", undone["outcome"].asText())
        assertEquals("INCOMPLETE_PASS", journal()["events"].last()["type"].asText())
        event("PICKUP", at.plusSeconds(2), mapOf("participantId" to member))
        event("GOAL", at.plusSeconds(3), mapOf("fromParticipantId" to member, "toParticipantId" to side(0)[1]))
        assertEquals("EVENT_SEQUENCE_VIOLATION", command(batchRoute, batch(journal(), set(member, 4)), 409)["code"].asText())
        event("HALFTIME_START", at.plusSeconds(4), emptyMap())
        assertEquals("EVENT_SEQUENCE_VIOLATION", command(batchRoute, batch(journal(), set(member, 5)), 409)["code"].asText())
    }

    @Test
    fun `transfer versions preserve remapped ownership and reject the legacy envelope`() {
        pull()
        val legacy = export()
        assertEquals(1, legacy["formatVersion"].asInt())
        command("/api/v1/matches/import", legacy, 200)
        val holder = side(0)[1]
        command(batchRoute, batch(journal(), set(holder, 1)), 201)
        val portable = export()
        assertEquals(3, portable["formatVersion"].asInt())
        val target = command("/api/v1/matches/import", portable, 200)
        val newMatch = target["matchIds"][match.id.toString()].asText()
        val newHolder = target["participantIds"][match.id.toString()][holder.toString()].asText()
        val copied = read("/api/v1/matches/$newMatch/events").last()
        assertEquals("POSSESSION_SET", copied["type"].asText())
        assertEquals(newHolder, copied["participantId"].asText())
        assertEquals(at.plusSeconds(1).toString(), copied["occurredAt"].asText())
        val count = matches.count()
        for (version in listOf(1, 2)) {
            assertEquals("INVALID_REQUEST", command("/api/v1/matches/import",
                portable.deepCopy().put("formatVersion", version), 400)["code"].asText())
            assertEquals(count, matches.count())
        }
    }

    @Test
    fun `enabled patch can correct the holder to either team and invalidates the creation retry`() {
        pull()
        val original = set(side(1)[0], 1)
        val applied = command(eventRoute, original, 201)
        val id = applied["id"].asText()
        val corrected = side(0)[0]
        val patchBody = mapOf("type" to "POSSESSION_SET", "participantId" to corrected)
        mvc.perform(patch("$eventRoute/$id").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(patchBody))).andExpect(status().isOk)
        val updated = journal()["events"].last()
        assertEquals(corrected.toString(), updated["participantId"].asText())
        for (key in listOf("id", "sequenceNumber", "occurredAt")) assertEquals(applied[key], updated[key])
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT", command(eventRoute, original, 409)["code"].asText())
    }

    private fun assertHold(stats: JsonNode, team: Int, holder: UUID, millis: Int) {
        val teamStats = stats["teams"][team]
        val player = teamStats["participants"].single { it["participantId"].asText() == holder.toString() }["time"]
        assertEquals(millis, teamStats["time"]["possessionTimeMs"].asInt())
        assertEquals(millis, player["possessionTimeMs"].asInt())
        assertEquals(millis, player["averagePossessionTimeMs"].asInt())
        assertTrue(player["averagePossessionTimeAvailable"].asBoolean())
    }

    private fun assertCounters(before: JsonNode, after: JsonNode) {
        for ((i, team) in before["teams"].withIndex()) {
            for (key in listOf("attack", "defense")) {
                assertEquals(team[key], after["teams"][i][key])
                assertEquals(team["participants"].map { it[key] }, after["teams"][i]["participants"].map { it[key] })
            }
        }
    }
}

@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
@SpringBootTest
class PossessionChangeWriteDisabledTest : PossessionChangeHttpFixture() {
    @Test
    fun `default gate denies both append routes but allows legacy writes and imports`() {
        pull()
        val before = journal("false")
        val neutral = set(side(1)[0], 1)
        val rejected = command(batchRoute, batch(before, set(side(1)[0], 1) + ("type" to "PICKUP"), neutral), 409)
        assertEquals("POSSESSION_CHANGE_WRITE_DISABLED", rejected["code"].asText())
        assertEquals(neutral["eventId"].toString(), rejected["eventId"].asText())
        assertEquals(1, rejected["eventIndex"].asInt())
        assertEquals("POSSESSION_CHANGE_WRITE_DISABLED", command(eventRoute, neutral, 409)["code"].asText())
        assertEquals(before, journal())
        val legacy = export()
        command("/api/v1/matches/import", legacy, 200)
        assertFalse(legacy["matches"][0].has("announcements"))
        (legacy["matches"][0]["events"][0] as ObjectNode).deepCopy()
            .put("type", "POSSESSION_SET").put("id", UUID.randomUUID().toString()).put("sequenceNumber", 2)
            .let { (legacy["matches"][0]["events"] as ArrayNode).add(it) }
        legacy.put("formatVersion", 3)
        val count = matches.count()
        assertEquals("POSSESSION_CHANGE_WRITE_DISABLED", command("/api/v1/matches/import", legacy, 409)["code"].asText())
        assertEquals(count, matches.count())
        event("PICKUP", at.plusSeconds(1), mapOf("participantId" to side(1)[0]))
    }
}

abstract class PossessionChangeHttpFixture : AnnouncementHttpFixture() {
    protected val journalRoute get() = eventRoute.replace("/v1/", "/v2/")
    protected val batchRoute get() = "$journalRoute/batch"
    protected fun side(index: Int) =
        matches.getOrThrow(match.id).participantsByTeam.getValue(match.teamIds[index]).map { it.participantId }

    protected fun pull() = event("PULL", at, mapOf("participantId" to side(0)[0]))
    protected fun stats() = read("/api/v1/matches/${match.id}/statistics")
    protected fun matchScore() = read("/api/v1/matches/${match.id}")["teams"].map { it["teamScore"].asInt() }
    protected fun export() = command("/api/v1/matches/export", mapOf("matchIds" to listOf(match.id)), 200) as ObjectNode

    protected fun journal(enabled: String? = null): JsonNode {
        val response = mvc.perform(get(journalRoute)).andExpect(status().isOk)
        if (enabled != null) {
            response.andExpect(header().string("X-UltiStats-Possession-Change-Enabled", enabled))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        }
        return mapper.readTree(response.andReturn().response.contentAsByteArray)
    }

    protected fun read(path: String): JsonNode = mapper.readTree(
        mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsByteArray,
    )

    protected fun set(player: UUID, seconds: Long) = mapOf(
        "type" to "POSSESSION_SET", "participantId" to player, "occurredAt" to at.plusSeconds(seconds),
        "eventId" to UUID.randomUUID(),
    )

    protected fun batch(base: JsonNode, vararg events: Map<String, Any>) = mapOf(
        "expectedLastSequenceNumber" to base["lastSequenceNumber"].asInt(),
        "expectedLogRevision" to base["logRevision"].asLong(),
        "events" to events.toList(),
    )

    protected fun undo(base: JsonNode) = command("$journalRoute/undo", mapOf(
        "commandId" to UUID.randomUUID(), "expectedLastEventId" to base["events"].last()["id"].asText(),
        "expectedLogRevision" to base["logRevision"].asLong(),
    ), 200)
}
