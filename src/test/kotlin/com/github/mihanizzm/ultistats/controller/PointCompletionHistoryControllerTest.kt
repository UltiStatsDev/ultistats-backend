package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.service.PointLineupClient
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AutoConfigureMockMvc
@Import(LegacyDomainHttpSecurity::class)
class PointCompletionHistoryControllerTest : MatchAbstractTest() {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val matchId = UUID.randomUUID()
    private val v1 = "/api/v1/matches/${matchId}/events"
    private val v2 = "/api/v2/matches/${matchId}/events"
    private val v3 = "/api/v3/matches/${matchId}/events"

    @Test
    fun `approved order preserves canonical rows and append cursor while drafts stay private`() {
        val (point, marker, pull, _, pickup, nextMarker) = fixture()
        val appendBefore = read(v2, 200)
        val initial = read(v3, 200)
        assertEquals(appendBefore["events"].toList(), initial["entries"].map { it["event"] })
        val draftOnly = insertion()
        jdbc.update(
            """INSERT INTO match_point_completion_drafts
               (match_id, point_id, closing_event_id, revision, base_log_revision, insertions)
               VALUES (?, ?, ?, 1, 7, ?)""",
            matchId, point, marker, json.writeValueAsString(listOf(draftOnly)),
        )
        assertEquals(initial, read(v3, 200))

        val atStart = insertion(offset = 0)
        val afterPull = insertion(after = pull)
        val alsoAfterPull = insertion(after = pull, offset = 1500)
        seed(point, marker, listOf(afterPull, atStart, alsoAfterPull))

        val projected = read(v3, 200)
        val entries = projected["entries"].toList()
        assertEquals((1..7).toList(), entries.map { it["position"].asInt() })
        assertEquals(listOf(atStart["id"], pull, afterPull["id"], alsoAfterPull["id"], pickup, marker, nextMarker)
            .map(Any?::toString), entries.map { (it["event"] ?: it["insertion"])["id"].asText() })
        assertEquals(listOf(0L, null, 1500L), entries.filter { it["kind"].asText() == "RESTORED" }.map {
            assertEquals(point.toString(), it["pointId"].asText())
            assertEquals(marker.toString(), it["closingEventId"].asText())
            val insertion = it["insertion"]
            assertTrue(insertion.has("elapsedOffsetMs"))
            assertFalse(insertion.has("occurredAt"))
            assertFalse(insertion.has("sequenceNumber"))
            insertion["elapsedOffsetMs"].takeUnless(JsonNode::isNull)?.asLong()
        })
        assertEquals(appendBefore["events"].toList(), entries.mapNotNull { it["event"] })
        assertEquals("2025-11-23T12:00:01.123456789Z", entries[1]["event"]["occurredAt"].asText())
        assertEquals(7, projected["logRevision"].asInt())
        assertEquals(5, projected["lastSequenceNumber"].asInt())
        assertEquals("POINT_COMPLETION_HISTORY_REQUIRED", read(v1, 409)["code"].asText())
        assertEquals("POINT_COMPLETION_HISTORY_REQUIRED", read(v2, 409)["code"].asText())
        assertEquals("POINT_RESULT_CLIENT_REQUIRED", read(v2, 409, "3")["code"].asText())
        assertEquals(appendBefore, read("$v2?view=append", 200))
        assertEquals(appendBefore["events"][2], read("$v1/$marker", 200))
        assertFalse(projected.toString().contains(draftOnly["id"].toString()))
    }

    @Test
    fun `invalid approved data refuses to produce a partial history`() {
        val (point, marker, pull, retired, _, nextMarker) = fixture()
        read(v3, 200)
        val duplicate = insertion()
        val secondPoint = PointTimeline.from(matchId, MatchStatus.FINISHED,
            eventService.getAllEventsOfMatch(matchId, PointLineupClient.POINT_RESULTS)).last().pointId
        seed(point, marker, listOf(duplicate))
        seed(secondPoint, nextMarker, listOf(duplicate))
        assertEquals("POINT_COMPLETION_HISTORY_CONFLICT", read(v3, 409)["code"].asText())
        jdbc.update("DELETE FROM match_point_completion_approvals WHERE match_id = ?", matchId)
        for ((pointId, markerId, inserts) in listOf(
            Triple(point, nextMarker, listOf(insertion())),
            Triple(UUID.randomUUID(), marker, listOf(insertion())),
            Triple(point, marker, listOf(insertion(after = retired))),
            Triple(point, marker, listOf(insertion(after = marker))),
            Triple(point, marker, listOf(insertion(after = nextMarker))),
            Triple(point, marker, listOf(insertion(id = retired))),
            Triple(point, marker, listOf(insertion(id = pull))),
            Triple(point, marker, listOf(insertion(actor = UUID.randomUUID()))),
        )) {
            seed(pointId, markerId, inserts)
            assertEquals("POINT_COMPLETION_HISTORY_CONFLICT", read(v3, 409)["code"].asText())
            assertEquals("POINT_COMPLETION_HISTORY_REQUIRED", read(v2, 409)["code"].asText())
            assertEquals(5, read("$v2?view=append", 200)["lastSequenceNumber"].asInt())
            jdbc.update("DELETE FROM match_point_completion_approvals WHERE match_id = ?", matchId)
        }
    }

    @Test
    fun `ordered history has nonrecursive OpenAPI schemas with distinct tags`() {
        val schemas = read("/v3/api-docs", 200)["components"]["schemas"]
        val union = schemas["ProjectedEvent"]
        assertEquals("kind", union["discriminator"]["propertyName"].asText())
        for ((kind, field) in listOf("ORIGINAL" to "event", "RESTORED" to "insertion")) {
            val name = union["discriminator"]["mapping"][kind].asText().substringAfterLast('/')
            val leaf = schemas[name]
            assertFalse(leaf.has("allOf"))
            val props = leaf["properties"]
            assertEquals(listOf(kind), props["kind"]["enum"].map(JsonNode::asText))
            assertTrue(props.has(field))
        }
    }

    private fun fixture(): FinishedFixture {
        matchService.create(Match(matchId, MATCH.teamIds))
        matchService.startMatch(matchId, Instant.parse(START_DATE))
        val ids = List(5) { UUID.randomUUID() }
        val stamp = OffsetDateTime.parse("2025-11-23T12:00:01.123456789Z")
        val types = listOf("PULL", "PICKUP", "PICKUP", "POINT_RESULT", "POINT_RESULT")
        types.forEachIndexed { index, type ->
            jdbc.update(
                """INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, original_occurred_at,
                   from_participant_id, team_id, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                ids[index], matchId, index + 1, type, stamp, stamp.toInstant().toString(),
                PLAYERS_1[0].id.takeUnless { type == "POINT_RESULT" },
                TEAM_1.id.takeIf { type == "POINT_RESULT" }, stamp.takeIf { index == 1 },
            )
        }
        jdbc.update("UPDATE matches SET ended_at = ?, event_log_revision = 7 WHERE id = ?",
            OffsetDateTime.parse(END_DATE), matchId)
        val point = PointTimeline.from(matchId, MatchStatus.FINISHED,
            eventService.getAllEventsOfMatch(matchId, PointLineupClient.POINT_RESULTS))
            .first().pointId
        return FinishedFixture(point, ids[3], ids[0], ids[1], ids[2], ids[4])
    }

    private data class FinishedFixture(val point: UUID, val marker: UUID, val pull: UUID, val retired: UUID,
                                       val pickup: UUID, val nextMarker: UUID)

    private fun insertion(after: UUID? = null, offset: Long? = null, id: UUID = UUID.randomUUID(),
                          actor: UUID = PLAYERS_1[0].id) = mapOf(
        "id" to id, "type" to "POSSESSION_SET", "fromActorId" to actor, "toActorId" to null,
        "afterEventId" to after, "elapsedOffsetMs" to offset,
    )

    private fun seed(point: UUID, marker: UUID, inserts: List<Map<String, Any?>>) {
        jdbc.update(
            """INSERT INTO match_point_completion_approvals (match_id, point_id, closing_event_id, insertions)
               VALUES (?, ?, ?, ?)""",
            matchId, point, marker, json.writeValueAsString(inserts),
        )
    }

    private fun read(path: String, status: Int, capability: String = "4"): JsonNode {
        val response = mvc.perform(get(path).header(POINT_LINEUPS_HEADER, capability)).andReturn().response
        assertEquals(status, response.status, "$path: ${response.contentAsString}")
        if (path.endsWith("?view=append")) assertEquals("append", response.getHeader("X-UltiStats-Event-View"))
        return json.readTree(response.contentAsByteArray)
    }
}
