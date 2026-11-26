package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.UltistatsApplication
import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.request.TeamEventRequest
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PointLineupClient
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.result.EventBatchResult
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class PointResultPostgresTest {
    private val matchId = UUID.randomUUID()
    private val teamA = UUID.randomUUID()
    private val teamB = UUID.randomUUID()
    private val v1 = "/api/v1/matches/$matchId/events"
    private val v2 = "/api/v2/matches/$matchId/events"
    private val startTime = Instant.parse("2026-07-28T12:00:00Z")
    private val markerId = UUID.randomUUID()
    private val finalMarkerId = UUID.randomUUID()
    private val keys = mapOf(
        "EXACT_EVENT_LOG" to "A".repeat(43),
        "EXACT_SCOREBOARD" to "B".repeat(43),
        "EXACT_INFOGRAPHICS" to "C".repeat(43),
    )
    private val pointsPath = "/api/v1/matches/$matchId/point-lineups"

    @Test
    fun `stored team results replay and project after a restart while ordinary writes stay closed`() {
        val oldScore: JsonNode
        val projected: JsonNode
        start().use { app ->
            val http = Client(app)
            val matches = app.getBean(MatchService::class.java)
            val teams = app.getBean(TeamService::class.java)
            teams.create(Team(teamA, "A"))
            teams.create(Team(teamB, "B"))
            matches.create(Match(matchId, listOf(teamA, teamB)))
            matches.startMatch(matchId, startTime)
            val roster = matches.getOrThrow(matchId).participantsByTeam
            val a = roster.getValue(teamA).map { it.participantId }
            val b = roster.getValue(teamB).map { it.participantId }

            val marker = mapOf("eventId" to markerId, "type" to "POINT_RESULT", "teamId" to teamA, "occurredAt" to at(1))
            val blank = http.get(v2)
            http.call("POST", v1, 400, marker)
            http.call("POST", "$v2/batch", 400, mapOf(
                "expectedLastSequenceNumber" to blank["lastSequenceNumber"],
                "expectedLogRevision" to blank["logRevision"],
                "events" to listOf(marker),
            ))
            val service = app.getBean(EventService::class.java)
            val markerType = EventType.POINT_RESULT
            assertIs<EventCommandResult.BadRequest>(service.create(TeamEvent(teamA, at(1), markerType), matchId, markerId))
            val typed = TeamEventRequest(markerType, at(1), teamA, markerId)
            assertIs<EventCommandResult.BadRequest>(service.create(matchId, typed, PointLineupClient.LEGACY))
            val internalBatch = CreateEventBatchRequest(blank["lastSequenceNumber"].asInt(), blank["logRevision"].asLong(), listOf(typed))
            assertEquals(0, assertIs<EventBatchResult.Rejected>(
                service.appendBatch(matchId, internalBatch, PointLineupClient.LEGACY),
            ).eventIndex)
            assertEquals(blank, http.get(v2))

            http.event("PULL", 2, "participantId" to a[0])
            http.event("PICKUP", 3, "participantId" to b[0])
            val legacyGoal = http.event("GOAL", 5, "fromParticipantId" to b[0], "toParticipantId" to b[1])
            oldScore = http.get("/api/v1/matches/$matchId/statistics")
            assertEquals(1, oldScore["teams"][1]["attack"]["score"].intValue())
            val afterGoal = http.get(v2)
            http.call("PATCH", "$v1/${legacyGoal["id"].asText()}", 400, marker - "eventId" - "occurredAt")
            val doc = http.call("POST", "/api/v1/matches/export", 200, mapOf("matchIds" to listOf(matchId)))
            val events = doc["matches"][0]["events"]
            val next = (events.last().deepCopy<ObjectNode>()).apply {
                put("id", markerId.toString())
                put("sequenceNumber", 4)
                put("type", "POINT_RESULT")
                put("teamId", teamA.toString())
                put("occurredAt", at(8).toString())
                putNull("fromParticipantId")
                putNull("toParticipantId")
            }
            val before = app.getBean(JdbcTemplate::class.java)
            val matchCount = before.queryForObject("SELECT count(*) FROM matches", Long::class.java)
            (events as ArrayNode).add(next)
            val rejection = http.call("POST", "/api/v1/matches/import", 400, doc)
            assertEquals("INVALID_REQUEST", rejection["code"].asText())
            assertEquals("matches[0].events[3].type: POINT_RESULT is unsupported in this format", rejection["detail"].asText())
            assertEquals(matchCount, before.queryForObject("SELECT count(*) FROM matches", Long::class.java))
            assertEquals(afterGoal, http.get(v2))

            val beforeResult = http.get(pointsPath)["points"]
            seedResult(before, markerId, 4, 8)
            val emptyResult = http.get(pointsPath)["points"]
            assertEquals(beforeResult[0], emptyResult[0])
            assertEquals(beforeResult[1]["pointId"], emptyResult[1]["pointId"])
            assertEquals(markerId.toString(), emptyResult[1]["closingEventId"].asText())
            assertEquals("NEXT", emptyResult[2]["status"].asText())
            assertEquals(pointId(markerId), emptyResult[2]["pointId"].asText())
            http.event("PULL", 40, "participantId" to a[0], capability = "4")
            val active = http.get(pointsPath)["points"][2]
            assertEquals(emptyResult[2]["pointId"], active["pointId"])
            assertEquals("ACTIVE", active["status"].asText())
            http.event("PICKUP", 42, "participantId" to b[0], capability = "4")
            http.event("PASS", 45, "fromParticipantId" to b[0], "toParticipantId" to b[1], capability = "4")
            seedResult(before, finalMarkerId, 8, 90)
            matches.recalculateScore(matchId)
            http.call("PATCH", "$v1/$finalMarkerId", 400, marker - "eventId" - "occurredAt", capability = "4")
            http.call("DELETE", "$v1/$finalMarkerId", 409, capability = "4")
            assertEquals(1, before.queryForObject("SELECT count(*) FROM events WHERE id = ? AND deleted_at IS NULL",
                Int::class.java, finalMarkerId))
            assertIs<MatchCommandResult.Success<*>>(matches.endMatch(matchId, at(100), PointLineupClient.POINT_RESULTS))
            for ((scope, key) in keys) {
                val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
                before.update(
                    "INSERT INTO obs_source_keys (key_id, match_id, scope, secret_digest, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    UUID.randomUUID(), matchId, scope, digest,
                )
            }
            projected = verify(http, oldScore)
        }

        start().use { app ->
            app.getBean(MatchService::class.java).recalculateScore(matchId)
            assertEquals(projected, verify(Client(app), oldScore))
        }
    }

    private fun verify(http: Client, old: JsonNode): JsonNode {
        val events = http.get(v1, capability = "4")
        assertEquals(events, http.get(v2, capability = "4")["events"])
        assertEquals(listOf(markerId.toString(), finalMarkerId.toString()),
            events.filter { it["type"].asText() == "POINT_RESULT" }.map { it["id"].asText() })
        assertEquals(setOf("id", "sequenceNumber", "type", "occurredAt", "teamId"),
            events[7].fieldNames().asSequence().toSet())
        assertEquals(teamA.toString(), events[7]["teamId"].asText())
        assertEquals(at(90), Instant.parse(events[7]["occurredAt"].asText()))

        val points = http.get(pointsPath)["points"]
        assertEquals(listOf(pointId(null), pointId(UUID.fromString(events[2]["id"].asText())), pointId(markerId)),
            points.map { it["pointId"].asText() })
        assertEquals(listOf(events[2]["id"].asText(), markerId.toString(), finalMarkerId.toString()),
            points.map { it["closingEventId"].asText() })

        val statistic = http.get("/api/v1/matches/$matchId/statistics")
        assertEquals(listOf(2, 1), statistic["teams"].map { it["attack"]["score"].asInt() })
        assertEquals(listOf(1, 0), statistic["teams"].map { it["attack"]["breaks"].asInt() })
        assertEquals(listOf(0, 2), statistic["teams"].map { it["attack"]["completePasses"].asInt() })
        assertEquals(listOf(0, 2), statistic["teams"].map { it["attack"]["allPasses"].asInt() })
        assertEquals(8_000, statistic["time"]["totalTimeMs"].asInt())
        assertEquals(0, statistic["time"]["betweenPointsTimeMs"].asInt())
        assertEquals(listOf(0, 5_000), statistic["teams"].map { it["time"]["possessionTimeMs"].asInt() })
        val previousPlayers = old["teams"].flatMap { it["participants"].toList() }.associateBy { it["participantId"] }
        statistic["teams"].flatMap { it["participants"].toList() }.forEach {
            val previous = previousPlayers.getValue(it["participantId"])
            for (name in listOf("goals", "assists")) {
                assertEquals(previous["attack"][name], it["attack"][name])
            }
            assertEquals(previous["defense"], it["defense"])
        }

        val log = http.get("/api/v1/obs/sources/event-log/$matchId", keys.getValue("EXACT_EVENT_LOG"))
        assertEquals(events, log["events"])
        assertEquals(listOf(2, 1), log["teams"].map { it["score"].asInt() })
        val scoreboard = http.get("/api/v1/obs/sources/scoreboard/$matchId", keys.getValue("EXACT_SCOREBOARD"))
        assertEquals(listOf(2, 1), scoreboard["teams"].map { it["score"].asInt() })
        assertEquals("FINISHED", scoreboard["status"].asText())
        val infographics = http.get("/api/v1/obs/sources/infographics/$matchId", keys.getValue("EXACT_INFOGRAPHICS"))
        assertFalse(infographics["postmatch"].isNull)
        assertEquals(listOf(2, 1), infographics["postmatch"]["teams"].map { it["score"].asInt() })
        assertEquals(listOf(1, 0), infographics["postmatch"]["teams"].map { it["metrics"]["breaks"].asInt() })
        return statistic
    }

    private fun seedResult(jdbc: JdbcTemplate, id: UUID, sequence: Int, seconds: Long) {
        jdbc.update(
            "INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, team_id) VALUES (?, ?, ?, 'POINT_RESULT', ?, ?)",
            id, matchId, sequence, OffsetDateTime.parse(at(seconds).toString()), teamA,
        )
    }

    private fun pointId(openingEventId: UUID?) =
        UUID.nameUUIDFromBytes("ultistats:point:v1:$matchId:${openingEventId ?: "first"}".encodeToByteArray()).toString()

    private fun at(seconds: Long) = startTime.plusSeconds(seconds)

    private fun start() = SpringApplicationBuilder(UltistatsApplication::class.java, LegacyDomainHttpSecurity::class.java)
        .profiles("postgres-list").run(
            "--server.port=0", "--server.address=127.0.0.1", "--app.auth.ingress.private-port=0",
        ) as ServletWebServerApplicationContext

    private inner class Client(app: ServletWebServerApplicationContext) {
        private val root = "http://127.0.0.1:${app.webServer.port}"
        private val mapper = app.getBean(ObjectMapper::class.java)
        private val http = HttpClient.newHttpClient()

        fun event(type: String, seconds: Long, vararg fields: Pair<String, UUID>, capability: String? = null) =
            call("POST", v1, 201, mapOf("type" to type, "occurredAt" to at(seconds)) + fields, capability = capability)

        fun get(path: String, key: String? = null, capability: String? = null) =
            call("GET", path, 200, key = key, capability = capability)

        fun call(method: String, path: String, status: Int, body: Any? = null, key: String? = null, capability: String? = null): JsonNode {
            val request = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(15))
                .header("X-Legacy-Domain-Test", "domain-contract")
            key?.let { request.header("Authorization", "Bearer $it") }
            capability?.let { request.header(POINT_LINEUPS_HEADER, it) }
            if (body != null) request.header("Content-Type", "application/json")
            request.method(method, body?.let { HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) }
                ?: HttpRequest.BodyPublishers.noBody())
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(status, response.statusCode(), response.body())
            return mapper.readTree(response.body())
        }
    }
}
