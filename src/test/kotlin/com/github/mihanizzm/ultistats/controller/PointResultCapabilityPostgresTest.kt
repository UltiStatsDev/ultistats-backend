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
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_POINT_RESULT_TLS_KEYSTORE", matches = ".+")
class PointResultCapabilityPostgresTest {
    private val matchId = UUID.randomUUID()
    private val teams = List(2) { UUID.randomUUID() }
    private val match = "/api/v1/matches/$matchId"
    private val v1 = "$match/events"
    private val v2 = "/api/v2/matches/$matchId/events"
    private val started = Instant.parse("2026-11-20T12:00:00Z")
    private val markerId = UUID.randomUUID()
    private val keystore = Path.of(System.getenv("TEST_POINT_RESULT_TLS_KEYSTORE"))

    @Test
    fun `canonical journal requires capability 4 after a marker and retains the cursor across restart`() {
        val restored: JsonNode
        start().use { app ->
            val http = Client(app)
            val matches = app.getBean(MatchService::class.java)
            val teamService = app.getBean(TeamService::class.java)
            teams.forEachIndexed { i, id -> teamService.create(Team(id, "Team $i")) }
            matches.create(Match(matchId, teams))
            http.call("POST", "$match/start", 200, body = mapOf("timestamp" to started))
            val puller = matches.getOrThrow(matchId).participantsByTeam.getValue(teams[0]).first().participantId
            val pull = mapOf("eventId" to UUID.randomUUID(), "type" to "PULL", "participantId" to puller,
                "occurredAt" to started.plusSeconds(1))
            val batch = batch(0, 0, pull)
            val ordinary = http.call("POST", "$v2/batch", 201, body = batch)
            for (capability in listOf(null, "1", "2", "3", "4")) {
                assertEquals(ordinary, http.call("GET", v2, 200, capability))
                assertEquals(ordinary, http.call("POST", "$v2/batch", 200, capability, batch))
                assertEquals(ordinary["events"], http.call("GET", v1, 200, capability))
            }

            val jdbc = app.getBean(JdbcTemplate::class.java)
            jdbc.update(
                "INSERT INTO events (id, match_id, sequence_number, event_type, occurred_at, team_id) VALUES (?, ?, 2, 'POINT_RESULT', ?, ?)",
                markerId, matchId, OffsetDateTime.parse(started.plusSeconds(2).toString()), teams[1],
            )
            jdbc.update("UPDATE matches SET event_log_revision = event_log_revision + 1 WHERE id = ?", matchId)
            matches.recalculateScore(matchId)
            val markerBody = mapOf("eventId" to UUID.randomUUID(), "type" to "POINT_RESULT", "teamId" to teams[1],
                "occurredAt" to started.plusSeconds(3))
            val unsupportedUndo = UUID.randomUUID()
            val undo = mapOf("commandId" to unsupportedUndo, "expectedLastEventId" to markerId, "expectedLogRevision" to 2)

            for (capability in listOf(null, "1", "2", "3", "5", "4,4")) {
                for (path in listOf(v1, v2, "$v1/$markerId", "$v1/${pull.getValue("eventId")}")) {
                    assertUnsupported(http.call("GET", path, 409, capability, testAuthority = false))
                }
            }
            assertEquals("PUBLIC_TRANSPORT_READ_ONLY",
                http.call("POST", v1, 403, body = "{", testAuthority = false)["code"].asText())
            assertUnsupported(http.call("POST", v1, 409, body = markerBody))
            val rejectedBatch = http.call("POST", "$v2/batch", 409, "3", batch)
            assertUnsupported(rejectedBatch)
            assertEquals(2, rejectedBatch["lastSequenceNumber"].asInt())
            assertEquals(2, rejectedBatch["logRevision"].asInt())
            assertUnsupported(http.call("PATCH", "$v1/$markerId", 409, "3", "{"))
            assertUnsupported(http.call("DELETE", "$v1/$markerId", 409))
            assertUnsupported(http.call("POST", "$v1/undo", 409))
            assertUnsupported(http.call("POST", "$v2/undo", 409, body = undo))
            http.call("GET", "$v2/undo/$unsupportedUndo", 404)
            assertUnsupported(http.call("POST", "$match/end", 409, body = mapOf("timestamp" to started.plusSeconds(4))))
            assertUnsupported(http.call("POST", "$match/start", 409, body = "{"))

            val canonical = http.call("GET", v2, 200, "4", testAuthority = false)
            assertEquals(2, canonical["lastSequenceNumber"].asInt())
            assertEquals(2, canonical["logRevision"].asInt())
            assertEquals(canonical["events"], http.call("GET", v1, 200, "4"))
            val marker = canonical["events"][1]
            assertEquals(setOf("id", "sequenceNumber", "type", "occurredAt", "teamId"), marker.fieldNames().asSequence().toSet())
            assertEquals("POINT_RESULT", marker["type"].asText())
            assertEquals(markerId.toString(), marker["id"].asText())
            assertEquals(teams[1].toString(), marker["teamId"].asText())
            assertEquals(marker, http.call("GET", "$v1/$markerId", 200, "4"))
            val retry = http.call("POST", "$v2/batch", 200, "4", batch)
            assertEquals(ordinary["events"], retry["events"])
            assertEquals(canonical["lastSequenceNumber"], retry["lastSequenceNumber"])
            assertEquals(canonical["logRevision"], retry["logRevision"])
            val summary = http.call("GET", match, 200, testAuthority = false)
            assertEquals(2, summary["eventCount"].asInt())
            assertEquals(1, summary["teams"][1]["teamScore"].asInt())
            assertFalse(summary.has("events"))
            assertEquals(1, http.call("GET", "$match/statistics", 200)["teams"][1]["attack"]["score"].asInt())

            http.call("POST", v1, 400, "4", markerBody)
            http.call("POST", "$v2/batch", 400, "4", batch(2, 2, markerBody))
            http.call("DELETE", "$v1/$markerId", 409, "4")
            assertEquals("POINT_RESULT_READ_ONLY",
                http.call("POST", "$v2/undo", 409, "4", undo)["code"].asText())
            assertEquals("POINT_RESULT_READ_ONLY",
                http.call("GET", "$v2/undo/$unsupportedUndo", 200)["rejection"]["code"].asText())
            assertEquals(canonical, http.call("GET", v2, 200, "4"))

            val nextPull = pull + mapOf("eventId" to UUID.randomUUID(), "occurredAt" to started.plusSeconds(3))
            val appended = http.call("POST", "$v2/batch", 201, "4", batch(2, 2, nextPull))
            val nextUndo = mapOf("commandId" to UUID.randomUUID(), "expectedLastEventId" to nextPull.getValue("eventId"),
                "expectedLogRevision" to appended["logRevision"])
            assertEquals("APPLIED", http.call("POST", "$v2/undo", 200, "4", nextUndo)["outcome"].asText())
            http.call("POST", "$match/end", 200, "4", mapOf("timestamp" to started.plusSeconds(4)))
            restored = http.call("GET", v2, 200, "4")
            assertEquals(canonical["events"], restored["events"])
            assertEquals(3, restored["lastSequenceNumber"].asInt())
            assertEquals(4, restored["logRevision"].asInt())
        }
        start().use { app ->
            val http = Client(app)
            assertEquals(restored, http.call("GET", v2, 200, "4"))
            assertUnsupported(http.call("GET", v2, 409))
            assertEquals("FINISHED", http.call("GET", match, 200)["status"].asText())
            System.getenv("TEST_POINT_RESULT_EXPORT_RECEIPT")?.let {
                val receipt = app.getBean(ObjectMapper::class.java).writeValueAsString(
                    mapOf("matchId" to matchId, "snapshot" to restored),
                )
                Files.writeString(Path.of(it), receipt)
            }
        }
    }

    private fun assertUnsupported(problem: JsonNode) {
        assertEquals("POINT_RESULT_CLIENT_REQUIRED", problem["code"].asText())
        assertEquals(409, problem["status"].asInt())
    }

    private fun batch(sequence: Int, revision: Int, event: Map<String, Any>) =
        mapOf("expectedLastSequenceNumber" to sequence, "expectedLogRevision" to revision, "events" to listOf(event))

    private fun start() = SpringApplicationBuilder(UltistatsApplication::class.java, LegacyDomainHttpSecurity::class.java)
        .profiles("postgres-list").run(
            "--server.port=0", "--server.address=127.0.0.1", "--app.auth.ingress.private-port=0",
            "--server.ssl.enabled=true", "--server.ssl.key-store=file:$keystore",
            "--server.ssl.key-store-password=changeit", "--server.ssl.key-store-type=PKCS12",
        ) as ServletWebServerApplicationContext

    private inner class Client(app: ServletWebServerApplicationContext) {
        private val root = "https://127.0.0.1:${app.webServer.port}"
        private val mapper = app.getBean(ObjectMapper::class.java)
        private val http = HttpClient.newBuilder().sslContext(
            SSLContext.getInstance("TLS").apply {
                val keys = KeyStore.getInstance(keystore.toFile(), "changeit".toCharArray())
                val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                trust.init(keys)
                init(null, trust.trustManagers, null)
            },
        ).build()

        fun call(
            method: String, path: String, status: Int, capability: String? = null, body: Any? = null,
            testAuthority: Boolean = true,
        ): JsonNode {
            val request = HttpRequest.newBuilder(URI.create(root + path)).timeout(Duration.ofSeconds(15))
            if (testAuthority) request.header("X-Legacy-Domain-Test", "domain-contract")
            capability?.let { request.header(POINT_LINEUPS_HEADER, it) }
            if (body != null) request.header("Content-Type", "application/json")
            val json = if (body is String) body else body?.let(mapper::writeValueAsString)
            request.method(method, json?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(status, response.statusCode(), "$method $path capability=$capability: ${response.body()}")
            assertTrue(response.sslSession().isPresent)
            val result = mapper.readTree(response.body())
            val journalRead = method == "GET" && status == 200 && (path.startsWith(v1) || path == v2)
            if (journalRead || result.path("code").asText() == "POINT_RESULT_CLIENT_REQUIRED") {
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
                assertTrue(response.headers().allValues("Vary").flatMap { it.split(',') }.map(String::trim).contains(POINT_LINEUPS_HEADER))
            }
            return result
        }
    }
}
