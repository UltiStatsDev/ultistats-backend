package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.service.PointLineupClient
import com.github.mihanizzm.ultistats.realtime.EventLogChangedSignal
import com.github.mihanizzm.ultistats.realtime.MatchChangedSignal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(LegacyDomainHttpSecurity::class)
@TestPropertySource(properties = ["ultistats.point-results.write-enabled=true"])
class PointCompletionHistoryControllerTest : MatchAbstractTest() {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var signals: ApplicationEvents

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
        val separateMatch = UUID.randomUUID()
        matchService.create(Match(separateMatch, MATCH.teamIds))
        matchService.startMatch(separateMatch, Instant.parse(START_DATE))
        jdbc.update(
            """INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, from_participant_id)
               VALUES (?, ?, 1, 'PULL', ?, ?)""",
            atStart["id"], separateMatch, OffsetDateTime.parse(START_DATE), PLAYERS_1[0].id,
        )

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

    @Test
    fun `approval validates journal candidate and exact replay keeps the accepted revision`() {
        val (point, marker, pull, retired, pickup, _) = fixture()
        val path = "/api/v1/matches/$matchId/point-lineups/$point/completion-draft"
        val body = mapOf(
            "operationId" to UUID.randomUUID(), "expectedClosingEventId" to marker,
            "expectedLogRevision" to 7, "expectedLineupRevision" to null, "expectedDraftRevision" to 1,
        )
        fun command(suffix: String, request: Map<String, Any?>, status: Int): JsonNode {
            val response = mvc.perform(put(path + suffix).header(POINT_LINEUPS_HEADER, "4")
                .contentType("application/json").content(json.valueToTree<ObjectNode>(request).putNull("expectedLineupRevision").toString())).andReturn().response
            assertEquals(status, response.status, response.contentAsString)
            return json.readTree(response.contentAsByteArray)
        }
        val wrong = command("", body.filterKeys { it != "operationId" } + mapOf("expectedDraftRevision" to 0, "insertions" to listOf(insertion())), 200)
        assertTrue(wrong["diagnostics"].any { it["code"].asText() == "MISSING_PERSONAL_RESULT" })
        val rejected = command("/approval", body, 409)
        assertEquals("POINT_COMPLETION_APPROVAL_CONFLICT", rejected["code"].asText())
        assertTrue(rejected["diagnostics"].any { it["code"].asText() == "MISSING_PERSONAL_RESULT" })
        assertEquals(7, read(v3, 200)["logRevision"].asInt())
        val save = body.filterKeys { it != "operationId" }
        assertEquals("INVALID_REQUEST", command("", save +
            mapOf("insertions" to listOf(insertion(id = retired))), 400)["code"].asText())
        val otherMatch = UUID.randomUUID()
        matchService.create(Match(otherMatch, MATCH.teamIds))
        matchService.startMatch(otherMatch, Instant.parse(START_DATE))
        val restoredId = UUID.randomUUID()
        jdbc.update("""INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, from_participant_id)
            VALUES (?, ?, 1, 'PULL', ?, ?)""", restoredId, otherMatch, OffsetDateTime.parse(START_DATE), PLAYERS_1[0].id)
        val goal = insertion(after = pickup, id = restoredId) +
            mapOf("type" to "GOAL", "toActorId" to PLAYERS_1[1].id)
        assertTrue(command("", save + ("insertions" to listOf(goal)), 200)["diagnostics"].isEmpty)
        assertEquals("POINT_COMPLETION_DRAFT_STALE", command("/approval", body, 409)["code"].asText())
        signals.clear()
        val accept = body + ("expectedDraftRevision" to 2)
        val accepted = command("/approval", accept, 200)
        assertEquals(8, accepted["logRevision"].asInt())
        assertEquals(5, accepted["lastSequenceNumber"].asInt())
        assertFalse(accepted["replayed"].asBoolean())
        val replay = command("/approval", accept, 200)
        assertEquals((accepted as ObjectNode).deepCopy().put("replayed", true), replay)
        assertEquals(1, signals.stream(MatchChangedSignal::class.java).count())
        assertEquals(0, signals.stream(EventLogChangedSignal::class.java).count())
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT",
            command("/approval", accept + ("expectedLogRevision" to 8), 409)["code"].asText())
        val history = read(v3, 200)
        assertEquals(8, history["logRevision"].asInt())
        assertEquals(restoredId.toString(), history["entries"].single { it["kind"].asText() == "RESTORED" }["insertion"]["id"].asText())
        val removal = mvc.perform(delete("$v1/$pull")
            .header(POINT_LINEUPS_HEADER, "4")).andReturn().response
        assertEquals(409, removal.status, removal.contentAsString)
        assertEquals("POINT_COMPLETION_APPROVAL_LOCKED", json.readTree(removal.contentAsByteArray)["code"].asText())
        assertEquals(history, read(v3, 200))

    }

    @Test
    fun `approved segment protects its opening boundary and permits removing an unrelated pull`() {
        val (point, marker, _, _, pickup, nextMarker) = fixture()
        val otherPull = UUID.randomUUID()
        jdbc.update("UPDATE events SET sequence_number = 6 WHERE id = ?", nextMarker)
        jdbc.update("""INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, from_participant_id)
            VALUES (?, ?, 5, 'PULL', ?, ?)""", otherPull, matchId, OffsetDateTime.parse("2025-11-23T12:00:02Z"), PLAYERS_1[0].id)
        seed(point, marker, listOf(insertion(after = pickup) +
            mapOf("type" to "GOAL", "toActorId" to PLAYERS_1[1].id)))
        val neighboring = mvc.perform(delete("$v1/$otherPull").header(POINT_LINEUPS_HEADER, "4")).andReturn().response
        assertEquals(204, neighboring.status, neighboring.contentAsString)
        assertEquals(6, read(v3, 200)["lastSequenceNumber"].asInt())

        jdbc.update("DELETE FROM match_point_completion_approvals WHERE match_id = ?", matchId)
        jdbc.update("""UPDATE events SET event_type = 'GOAL', team_id = NULL,
            from_participant_id = ?, to_participant_id = ? WHERE id = ?""", PLAYERS_1[0].id, PLAYERS_1[1].id, marker)
        val second = PointTimeline.from(matchId, MatchStatus.FINISHED,
            eventService.getAllEventsOfMatch(matchId, PointLineupClient.POINT_RESULTS)).last().pointId
        seed(second, nextMarker, listOf(insertion()))
        val protected = mvc.perform(delete("$v1/$marker").header(POINT_LINEUPS_HEADER, "4")).andReturn().response
        assertEquals(409, protected.status, protected.contentAsString)
        assertEquals("POINT_COMPLETION_APPROVAL_LOCKED", json.readTree(protected.contentAsByteArray)["code"].asText())
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
                (if (type == "PULL") PLAYERS_2[0].id else PLAYERS_1[0].id).takeUnless { type == "POINT_RESULT" },
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
