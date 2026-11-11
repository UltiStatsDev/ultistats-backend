package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(LegacyDomainHttpSecurity::class)
@AutoConfigureMockMvc
@SpringBootTest(properties = [
    "ultistats.announcements.write-enabled=true",
    "ultistats.possession-change.write-enabled=true",
])
open class AnnouncementStatisticsControllerTest : AnnouncementHttpFixture() {
    private val offense get() = match.teamIds[1]
    private val attack get() = matches.getOrThrow(match.id).participantsByTeam.getValue(offense)
    private val holder get() = attack[0].participantId
    private val receiver get() = attack[1].participantId

    @Test
    fun `six plus eight plus four seconds make one ten second hold`() {
        startPoint()
        goal(18)
        val baseline = statistics()
        assertTiming(baseline, wall = 18_000, pure = 18_000, possession = 18_000, average = 18_000)
        assertSummary(baseline, knownCount = 0, knownMs = 0, unknownCount = 0, unknownMs = 0)

        val id = startCall(6, offense)
        val closed = endCall(id, 14)
        assertEquals(closed, endCall(id, 14))
        assertEquals(closed, command(route, mapOf("id" to id, "teamId" to offense, "startedAt" to at.plusSeconds(6)), 200))
        val timed = statistics()
        assertTiming(timed, wall = 18_000, pure = 10_000, possession = 10_000, average = 10_000)
        assertSummary(timed, knownCount = 1, knownMs = 8_000, unknownCount = 0, unknownMs = 0)
        assertEquals(baseline["teams"][1]["attack"], timed["teams"][1]["attack"])
    }

    @Test
    fun `full and partial overlap with existing pauses excludes each instant once`() {
        startPoint()
        event("TIMEOUT_START", at.plusSeconds(6), mapOf("teamId" to offense))
        event("TIMEOUT_END", at.plusSeconds(10), mapOf("teamId" to offense))
        goal(20)
        event("HALFTIME_START", at.plusSeconds(23), emptyMap())
        event("HALFTIME_END", at.plusSeconds(30), emptyMap())
        pull(34)
        val baseline = statistics()
        assertTiming(baseline, wall = 34_000, pure = 19_000, possession = 16_000, average = 16_000)
        endCall(startCall(4), 8)
        endCall(startCall(9, offense), 10)
        endCall(startCall(20, offense), 22)
        endCall(startCall(24), 27)
        endCall(startCall(29), 32)
        val timed = statistics()
        assertTiming(timed, wall = 34_000, pure = 15_000, possession = 14_000, average = 14_000)
        assertEquals(baseline["time"]["timeoutTimeMs"], timed["time"]["timeoutTimeMs"])
        assertEquals(baseline["time"]["halftimeTimeMs"], timed["time"]["halftimeTimeMs"])
        assertEquals(baseline["time"]["betweenPointsTimeMs"], timed["time"]["betweenPointsTimeMs"])
        assertSummary(timed, knownCount = 2, knownMs = 3_000, unknownCount = 3, unknownMs = 10_000)
    }

    @Test
    fun `legacy and standalone summaries count distinct completed intervals`() {
        pull(3)
        event("PICKUP", at.plusSeconds(3), mapOf("participantId" to holder))
        event("ANNOUNCEMENT_START", at.plusSeconds(5), emptyMap())
        event("ANNOUNCEMENT_END", at.plusSeconds(9), emptyMap())
        goal(13)
        val legacy = statistics()
        assertTiming(legacy, wall = 10_000, pure = 6_000, possession = 6_000, average = 6_000)
        assertSummary(legacy, knownCount = 0, knownMs = 0, unknownCount = 1, unknownMs = 4_000)

        endCall(startCall(0, offense), 1)
        endCall(startCall(4, offense), 7)
        val combined = statistics()
        assertTiming(combined, wall = 10_000, pure = 5_000, possession = 5_000, average = 5_000)
        assertSummary(combined, knownCount = 2, knownMs = 4_000, unknownCount = 1, unknownMs = 4_000)
    }

    @Test
    fun `undo restores ownership while open call uses only measured time`() {
        startPoint()
        val id = startCall(6)
        event("PASS", at.plusSeconds(10), mapOf("fromParticipantId" to holder, "toParticipantId" to receiver))
        val before = statistics()
        assertTiming(before, wall = 10_000, pure = 6_000, possession = 6_000, average = 6_000)
        assertSummary(before, knownCount = 0, knownMs = 0, unknownCount = 0, unknownMs = 0)

        command("$eventRoute/undo", null, 200)
        assertTrue(list().single()["endedAt"].isNull)
        assertTiming(statistics(), wall = 0, pure = 0, possession = 0, average = null)
        endCall(id, 14)
        goal(18)
        val after = statistics()
        assertTiming(after, wall = 18_000, pure = 10_000, possession = 10_000, average = 10_000)
        assertSummary(after, knownCount = 0, knownMs = 0, unknownCount = 1, unknownMs = 8_000)
    }

    @Test
    fun `correction only moves attribution buckets in combined statistics`() {
        startPoint()
        event("ANNOUNCEMENT_START", at.plusSeconds(5), emptyMap())
        event("ANNOUNCEMENT_END", at.plusSeconds(9), emptyMap())
        val id = startCall(4)
        endCall(id, 7)
        goal(13)
        command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(15)), 200)
        val before = statistics()
        val journal = getJson("/api/v2/matches/${match.id}/events")
        val matchDetail = getJson("/api/v1/matches/${match.id}")
        assertSummary(before, knownCount = 0, knownMs = 0, unknownCount = 2, unknownMs = 7_000)

        correct(id, mapOf("teamId" to offense, "expectedAttributionRevision" to 0), 200)

        val after = statistics()
        assertSummary(after, knownCount = 1, knownMs = 3_000, unknownCount = 1, unknownMs = 4_000)
        (after["announcements"] as ObjectNode).setAll<ObjectNode>(
            mapOf("byTeam" to before["announcements"]["byTeam"], "unknown" to before["announcements"]["unknown"]),
        )
        assertEquals(before, after)
        assertEquals(journal, getJson("/api/v2/matches/${match.id}/events"))
        assertEquals(matchDetail, getJson("/api/v1/matches/${match.id}"))
    }

    @Test
    fun `portable versions two and three preserve effective team with fresh revision`() {
        for (version in listOf(2, 3)) {
            createMatch()
            startPoint()
            if (version == 3) event("POSSESSION_SET", at.plusSeconds(1), mapOf("participantId" to holder))
            val id = startCall(2, match.teamIds[0])
            endCall(id, 5)
            goal(10)
            command("/api/v1/matches/${match.id}/end", mapOf("timestamp" to at.plusSeconds(11)), 200)
            correct(id, mapOf("teamId" to offense, "expectedAttributionRevision" to 0), 200)
            val exported = command("/api/v1/matches/export", mapOf("matchIds" to listOf(match.id)), 200)
            assertEquals(version, exported["formatVersion"].asInt())
            val interval = exported["matches"][0]["announcements"].single()
            assertEquals(setOf("id", "teamId", "startedAt", "endedAt"), interval.fieldNames().asSequence().toSet())
            assertEquals(offense.toString(), interval["teamId"].asText())
            val imported = command("/api/v1/matches/import", exported, 200)
            val copiedId = imported["matchIds"][match.id.toString()].asText()
            val copiedTeam = imported["teamIds"][offense.toString()].asText()
            val copied = getJson("/api/v1/matches/$copiedId/announcements").single()
            assertEquals(copiedTeam, copied["teamId"].asText())
            assertEquals(0, copied["attributionRevision"].asInt())
            assertEquals(id.toString(), copied["id"].asText())
            assertEquals(interval["startedAt"], copied["startedAt"])
            assertEquals(interval["endedAt"], copied["endedAt"])
        }
    }

    @Test
    fun `match transfer preserves exact interval identity and remaps attribution`() {
        startPoint()
        val exportPath = "/api/v1/matches/export"
        val selection = mapOf("matchIds" to listOf(match.id))
        val withoutCalls = command(exportPath, selection, 200)
        assertEquals(1, withoutCalls["formatVersion"].asInt())
        assertFalse(withoutCalls["matches"][0].has("announcements"))
        val id = UUID.randomUUID()
        val start = at.plusSeconds(2).plusNanos(123456789)
        val end = at.plusSeconds(5).plusNanos(987654321)
        command(route, mapOf("id" to id, "teamId" to offense, "startedAt" to start), 201)
        command("$route/$id/end", mapOf("endedAt" to end), 200)
        startCall(8)
        goal(10)
        val source = command(exportPath, selection, 200) as ObjectNode
        assertEquals(2, source["formatVersion"].asInt())
        val intervals = source["matches"][0]["announcements"]
        assertEquals(intervals, portableProjection(list()))
        val shuffled = source.deepCopy()
        (shuffled["matches"][0] as ObjectNode).withArray("announcements").removeAll().addAll(intervals.reversed())
        val result = command("/api/v1/matches/import", shuffled, 200)
        val newMatchId = result["matchIds"][match.id.toString()].asText()
        val newOffense = result["teamIds"][offense.toString()].asText()
        val copy = getJson("/api/v1/matches/$newMatchId/announcements")
        assertEquals(intervals, portableProjection(copy).also {
            (it[0] as ObjectNode).put("teamId", offense.toString())
        })
        assertEquals(newOffense, copy[0]["teamId"].asText())
        assertEquals(id.toString(), copy[0]["id"].asText())
        assertEquals(start.toString(), copy[0]["startedAt"].asText())
        assertEquals(end.toString(), copy[0]["endedAt"].asText())
        assertTrue(copy[1]["teamId"].isNull)
        assertTrue(copy[1]["endedAt"].isNull)
        val copiedStats = getJson("/api/v1/matches/$newMatchId/statistics")
        assertEquals(statistics()["time"], copiedStats["time"])

        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("formatVersion", 1) },
            { (it["matches"][0]["announcements"][1] as ObjectNode).put("startedAt", at.toString()) },
            { (it["matches"][0]["announcements"][0] as ObjectNode).putNull("endedAt") },
            { (it["matches"][0]["announcements"][0] as ObjectNode).put("endedAt", at.toString()) },
            { (it["matches"][0]["announcements"][0] as ObjectNode).put("teamId", UUID.randomUUID().toString()) },
            { (it["matches"][0]["announcements"][0] as ObjectNode).put("id", intervals[1]["id"].asText()) },
            { (it["matches"][0] as ObjectNode).put("endedAt", at.plusSeconds(11).toString()) },
        )
        val matchCount = matches.count()
        for (mutate in mutations) {
            val invalid = source.deepCopy()
            mutate(invalid)
            assertEquals("INVALID_REQUEST", command("/api/v1/matches/import", invalid, 400)["code"].asText())
            assertEquals(matchCount, matches.count())
        }
    }

    private fun portableProjection(list: JsonNode) = list.deepCopy<JsonNode>().also {
        it.forEach { row -> (row as ObjectNode).remove("attributionRevision") }
    }

    private fun startPoint() {
        pull(0)
        event("PICKUP", at, mapOf("participantId" to holder))
    }

    private fun pull(seconds: Long) {
        val puller = matches.getOrThrow(match.id).participantsByTeam.getValue(match.teamIds[0])[0].participantId
        event("PULL", at.plusSeconds(seconds), mapOf("participantId" to puller))
    }

    private fun goal(seconds: Long) = event(
        "GOAL", at.plusSeconds(seconds), mapOf("fromParticipantId" to holder, "toParticipantId" to receiver),
    )

    private fun startCall(seconds: Long, teamId: UUID? = null): UUID = UUID.randomUUID().also {
        command(route, mapOf("id" to it, "teamId" to teamId, "startedAt" to at.plusSeconds(seconds)), 201)
    }

    private fun endCall(id: UUID, seconds: Long) = command("$route/$id/end", mapOf("endedAt" to at.plusSeconds(seconds)), 200)

    private fun statistics(): JsonNode = mapper.readTree(
        mvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andDo(print())
            .andExpect(status().isOk)
            .andExpect(header().string("X-UltiStats-Announcement-Statistics", "combined-v1"))
            .andReturn().response.contentAsByteArray,
    )

    private fun getJson(path: String): JsonNode = mapper.readTree(
        mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsByteArray,
    )

    private fun assertTiming(stats: JsonNode, wall: Int, pure: Int, possession: Int, average: Int?) {
        val time = stats["time"]
        assertEquals(wall, time["totalTimeMs"].asInt(), "wall")
        assertEquals(pure, time["pureGameTimeMs"].asInt(), "pure")
        assertEquals(possession, stats["teams"][1]["time"]["possessionTimeMs"].asInt(), "team possession")
        val playerTime = stats["teams"][1]["participants"].single { it["participantId"].asText() == holder.toString() }["time"]
        assertEquals(possession, playerTime["possessionTimeMs"].asInt(), "player possession")
        assertEquals(average != null, playerTime["averagePossessionTimeAvailable"].asBoolean(), "average availability")
        assertEquals(average ?: 0, playerTime["averagePossessionTimeMs"].asInt(), "average")
    }

    private fun assertSummary(stats: JsonNode, knownCount: Int, knownMs: Int, unknownCount: Int, unknownMs: Int) {
        val calls = stats["announcements"]
        assertEquals(knownCount + unknownCount, calls["count"].asInt(), "all calls")
        assertEquals(knownMs + unknownMs, calls["durationMs"].asInt(), "all call duration")
        assertEquals(match.teamIds.map(UUID::toString), calls["byTeam"].map { it["teamId"].asText() })
        assertEquals(knownCount, calls["byTeam"][1]["count"].asInt(), "team calls")
        assertEquals(knownMs, calls["byTeam"][1]["durationMs"].asInt(), "team call duration")
        assertEquals(unknownCount, calls["unknown"]["count"].asInt(), "unknown calls")
        assertEquals(unknownMs, calls["unknown"]["durationMs"].asInt(), "unknown call duration")
    }
}

@ActiveProfiles("postgres-list")
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AnnouncementStatisticsPostgresTest : AnnouncementStatisticsControllerTest()
