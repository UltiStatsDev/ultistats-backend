package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.UltistatsApplication
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in: exercise both settings against the same isolated, Flyway-migrated PostgreSQL data. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class PossessionChangePostgresTest {
    @Test
    fun `flag off restart preserves both journals exact retry and undo on the same database`() {
        val id = UUID.randomUUID()
        val v1 = "/api/v1/matches/$id/events"
        val v2 = "/api/v2/matches/$id/events"
        val at = Instant.parse("2026-07-28T12:00:00Z")
        val neutralId = UUID.randomUUID()
        val statsPath = "/api/v1/matches/$id/statistics"
        val original = start(true).use { app ->
            val teams = app.getBean(TeamService::class.java)
            val matchService = app.getBean(MatchService::class.java)
            val teamIds = listOf("First", "Second").map { Team(UUID.randomUUID(), it).also(teams::create).id }
            matchService.create(Match(id, teamIds))
            matchService.startMatch(id, at)
            val roster = matchService.getOrThrow(id).participantsByTeam
            val first = roster.getValue(teamIds[1]).first().participantId
            val next = roster.getValue(teamIds[0]).first().participantId
            val http = Client(app)
            http.get("/v3/api-docs", "openapi")
            http.json("POST", v1, 201, mapOf("type" to "PULL", "participantId" to next, "occurredAt" to at))
            http.json("POST", v1, 201, mapOf("type" to "PICKUP", "participantId" to first, "occurredAt" to at))
            http.get(v1, "before-v1")
            val base = http.get(v2, "before-v2", enabled = "true")
            val neutral = mapOf(
                "type" to "POSSESSION_SET", "participantId" to next, "occurredAt" to at.plusSeconds(6), "eventId" to neutralId,
            )
            val batch = mapOf(
                "expectedLastSequenceNumber" to base["lastSequenceNumber"].asInt(),
                "expectedLogRevision" to base["logRevision"].asLong(),
                "events" to listOf(neutral),
            )
            val applied = http.json("POST", "$v2/batch", 201, batch, evidence = "on-batch")
            val onV1 = http.get(v1, "on-v1")
            val onV2 = http.get(v2, "on-v2", enabled = "true")
            assertEquals(neutralId.toString(), onV1.last()["id"].asText())
            assertEquals(onV1, onV2["events"])
            Original(neutral, batch, applied, onV1, onV2, http.get(statsPath, "on-stats"), first)
        }

        start(false).use { app ->
            val http = Client(app)
            assertEquals(original.v1, http.get(v1, "off-v1"))
            assertEquals(original.v2, http.get(v2, "off-v2", enabled = "false"))
            assertEquals(original.stats, http.get(statsPath, "off-stats"))
            assertEquals(original.applied, http.json("POST", "$v2/batch", 200, original.batch, "off-batch-retry"))
            assertEquals(original.v1.last(), http.json("POST", v1, 200, original.neutral, "off-v1-retry"))

            val fresh = original.neutral + ("eventId" to UUID.randomUUID())
            val request = original.batch + mapOf(
                "expectedLastSequenceNumber" to original.v2["lastSequenceNumber"].asInt(),
                "expectedLogRevision" to original.v2["logRevision"].asLong(),
                "events" to listOf(fresh),
            )
            val patch = mapOf("type" to "POSSESSION_SET", "participantId" to original.previousHolder)
            val portable = http.json("POST", "/api/v1/matches/export", 200, mapOf("matchIds" to listOf(id)), "off-export")
            assertEquals(3, portable["formatVersion"].asInt())
            for ((method, path, body) in listOf(
                Triple("POST", "$v2/batch", request), Triple("PATCH", "$v1/$neutralId", patch),
                Triple("POST", "/api/v1/matches/import", portable),
            )) {
                val name = if (method == "PATCH") "patch" else path.substringAfterLast('/')
                val rejected = http.json(method, path, 409, body, "off-rejected-$name")
                assertEquals("POSSESSION_CHANGE_WRITE_DISABLED", rejected["code"].asText())
            }
            assertEquals(original.v2, http.get(v2, "off-invariant-v2", enabled = "false"))

            val undo = mapOf("commandId" to UUID.randomUUID(), "expectedLastEventId" to neutralId,
                "expectedLogRevision" to original.v2["logRevision"].asLong())
            val outcome = http.json("POST", "$v2/undo", 200, undo, "off-undo")
            assertEquals("APPLIED", outcome["outcome"].asText())
            assertEquals(outcome, http.json("POST", "$v2/undo", 200, undo, "off-undo-retry"))
            val afterUndo = http.get(v2, "off-after-undo", enabled = "false")
            assertEquals(original.v1.take(2), afterUndo["events"].toList())
            assertEquals(original.v2["lastSequenceNumber"], afterUndo["lastSequenceNumber"])
            assertEquals(original.v2["logRevision"].asLong() + 1, afterUndo["logRevision"].asLong())
            assertEquals("EVENT_IDEMPOTENCY_CONFLICT", http.json("POST", "$v2/batch", 409,
                original.batch, "off-retry-after-undo")["code"].asText())
            val receiver = app.getBean(MatchService::class.java).getOrThrow(id).participantsByTeam.values
                .single { team -> team.any { it.participantId == original.previousHolder } }
                .first { it.participantId != original.previousHolder }.participantId
            http.json("POST", v1, 201, mapOf("type" to "GOAL", "occurredAt" to at.plusSeconds(10),
                "fromParticipantId" to original.previousHolder, "toParticipantId" to receiver), "off-goal")
            val restored = http.get(statsPath, "off-after-undo-stats")
            val holderStats = restored["teams"].flatMap { it["participants"].toList() }
                .single { it["participantId"].asText() == original.previousHolder.toString() }
            assertEquals(10_000, holderStats["time"]["averagePossessionTimeMs"].asInt())
        }
    }

    private fun start(enabled: Boolean) = SpringApplicationBuilder(
        UltistatsApplication::class.java, LegacyDomainHttpSecurity::class.java,
    ).profiles("postgres-list").run(
        "--server.port=0", "--server.address=127.0.0.1", "--app.auth.ingress.private-port=0",
        "--ultistats.possession-change.write-enabled=$enabled",
    ) as ServletWebServerApplicationContext

    private data class Original(
        val neutral: Map<String, Any>, val batch: Map<String, Any>, val applied: JsonNode,
        val v1: JsonNode, val v2: JsonNode, val stats: JsonNode, val previousHolder: UUID,
    )

    private class Client(app: ServletWebServerApplicationContext) {
        private val root = "http://127.0.0.1:${app.webServer.port}"
        private val mapper = app.getBean(ObjectMapper::class.java)
        private val http = HttpClient.newHttpClient()

        fun get(path: String, evidence: String, enabled: String? = null): JsonNode {
            val response = request("GET", path, null, evidence, legacySecurity = false)
            assertEquals(200, response.statusCode(), response.body())
            if (enabled != null) {
                assertEquals(enabled, response.headers().firstValue("X-UltiStats-Possession-Change-Enabled").orElse(null))
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
                assertEquals("https://localhost:8443", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
                assertTrue(response.headers().firstValue("Access-Control-Expose-Headers").orElse("")
                    .contains("X-UltiStats-Possession-Change-Enabled"))
            }
            return mapper.readTree(response.body())
        }

        fun json(method: String, path: String, status: Int, body: Any, evidence: String? = null): JsonNode {
            val response = request(method, path, body, evidence)
            assertEquals(status, response.statusCode(), response.body())
            return mapper.readTree(response.body())
        }

        private fun request(
            method: String, path: String, body: Any?, evidence: String?, legacySecurity: Boolean = true,
        ): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(15))
            if (legacySecurity || path.endsWith("/statistics") || path == "/v3/api-docs") {
                builder.header("X-Legacy-Domain-Test", "domain-contract")
            } else {
                builder.header("Origin", "https://localhost:8443")
            }
            val request = if (body == null) builder.GET() else builder
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            val directory = System.getenv("POSSESSION_CHANGE_PROOF_DIR")
            if (directory != null && evidence != null) {
                val dest = Files.createDirectories(Path.of(directory))
                Files.writeString(dest.resolve("$evidence.json"), response.body())
                Files.writeString(dest.resolve("$evidence.headers"), "${response.statusCode()} ${response.headers().map()}\n")
            }
            return response
        }
    }
}
