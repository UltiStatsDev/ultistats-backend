package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.UltistatsApplication
import com.github.mihanizzm.ultistats.auth.model.AuthLoginResult
import com.github.mihanizzm.ultistats.auth.model.AuthRole
import com.github.mihanizzm.ultistats.auth.model.CreateAccountRequest
import com.github.mihanizzm.ultistats.auth.service.AuthService
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt in with an isolated PostgreSQL database. All HTTP requests use the production security chain. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AnnouncementAttributionPostgresTest {
    private val schema = "announcement_" + UUID.randomUUID().toString().replace("-", "")
    private val at = Instant.parse("2026-07-28T12:00:00.123456789Z")
    private val matchId = UUID.randomUUID()
    private val known = UUID.randomUUID()
    private val unknown = UUID.randomUUID()
    private val path = "/api/v1/matches/$matchId/announcements"
    private val statsPath = "/api/v1/matches/$matchId/statistics"

    @Test
    fun `V12 rows survive migration corrections and disabled restart through signed HTTP`() {
        val (teamIds, admin, organizer) = start(enabled = false, target = "12").use { app ->
            val teams = app.getBean(TeamService::class.java)
            val ids = listOf("First", "Second").map { Team(UUID.randomUUID(), it).also(teams::create).id }
            val matches = app.getBean(MatchService::class.java)
            matches.create(Match(matchId, ids, startedAt = at))
            val jdbc = app.getBean(JdbcTemplate::class.java)
            assertEquals("12", jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                String::class.java,
            ))
            for ((id, team, seconds) in listOf(Triple(known, ids[0], 1L), Triple(unknown, null, 4L))) {
                jdbc.update(
                    """INSERT INTO match_announcements
                        (match_id, id, team_id, started_second, started_nano, ended_second, ended_nano)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""",
                    matchId, id, team, at.epochSecond + seconds, at.nano, at.epochSecond + seconds + 2, at.nano,
                )
            }
            val auth = app.getBean(AuthService::class.java)
            auth.ownerBootstrap("owner", "Owner", "owner-temporary")
            val owner = auth.login("owner", "owner-temporary", null)
            val admin = auth.changePassword(auth.principal(owner.credential), "owner-temporary", "owner-permanent")
            val account = auth.createAccount(auth.principal(admin.credential), CreateAccountRequest("organizer", "Organizer", AuthRole.ORGANIZER))
            val temp = auth.login(account.account.login, account.temporaryPassword, null)
            val organizer = auth.changePassword(auth.principal(temp.credential), account.temporaryPassword, "organizer-permanent")
            val roster = matches.getOrThrow(matchId).participantsByTeam
            val offense = roster.getValue(ids[1]).map { it.participantId }
            val events = "/api/v1/matches/$matchId/events"
            val http = Client(app)
            http.call("POST", events, 201, mapOf("type" to "PULL", "occurredAt" to at,
                "participantId" to roster.getValue(ids[0]).first().participantId), organizer)
            http.call("POST", events, 201, mapOf("type" to "PICKUP", "occurredAt" to at,
                "participantId" to offense[0]), organizer)
            http.call("POST", events, 201, mapOf("type" to "GOAL", "occurredAt" to at.plusSeconds(7),
                "fromParticipantId" to offense[0], "toParticipantId" to offense[1]), organizer)
            // Represent a match completed by the V12 application before upgrading the database.
            val endedAt = at.plusSeconds(8)
            jdbc.update("UPDATE matches SET ended_at = ?, original_ended_at_second = ?, original_ended_at_nano = ? WHERE id = ?",
                endedAt.atOffset(ZoneOffset.UTC), endedAt.epochSecond, endedAt.nano, matchId)
            Triple(ids, admin, organizer)
        }

        val corrected = start(enabled = true).use { app ->
            val http = Client(app)
            val initial = http.call("GET", path, 200, evidence = "list-before")
            assertEquals(listOf(0, 0), initial.map { it["attributionRevision"].asInt() })
            assertEquals(teamIds[0].toString(), initial[0]["teamId"].asText())
            assertTrue(initial[1]["teamId"].isNull)
            val openapi = http.call("GET", "/v3/api-docs", 200, login = admin, evidence = "openapi")
            val operation = openapi["paths"]["/api/v1/matches/{matchId}/announcements/{id}/team"]["patch"]
            assertTrue(operation["requestBody"]["required"].asBoolean())
            val request = openapi["components"]["schemas"]["CorrectAnnouncementTeamRequest"]
            assertEquals(setOf("teamId", "expectedAttributionRevision"), request["required"].map { it.asText() }.toSet())
            assertEquals(setOf("string", "null"), request["properties"]["teamId"]["type"].map { it.asText() }.toSet())
            val body = mapOf("teamId" to teamIds[1], "expectedAttributionRevision" to 0)
            val target = "$path/$unknown/team"
            assertEquals("AUTH_REQUIRED", http.call("PATCH", target, 401, body, evidence = "guest")["code"].asText())
            assertEquals("CSRF_INVALID", http.call("PATCH", target, 403, body, organizer, evidence = "csrf", csrf = false)["code"].asText())
            val applied = http.call("PATCH", target, 200, body, organizer, "patch-success")
            assertEquals(teamIds[1].toString(), applied["teamId"].asText())
            assertEquals(1, applied["attributionRevision"].asInt())
            assertEquals("ANNOUNCEMENT_ATTRIBUTION_STALE",
                http.call("PATCH", target, 409, body, admin, "patch-stale")["code"].asText())
            val updatedKnown = http.call("PATCH", "$path/$known/team", 200, body, admin)
            assertEquals(teamIds[1].toString(), updatedKnown["teamId"].asText())
            val list = http.call("GET", path, 200, evidence = "list-after")
            assertEquals(listOf(updatedKnown, applied), list.toList())
            val stats = http.call("GET", statsPath, 200, evidence = "statistics")
            assertEquals(2, stats["announcements"]["byTeam"][1]["count"].asInt())
            assertEquals(4_000, stats["announcements"]["byTeam"][1]["durationMs"].asInt())
            assertEquals(0, stats["announcements"]["unknown"]["count"].asInt())
            list
        }

        start(enabled = false).use { app ->
            val http = Client(app)
            val off = http.call("PATCH", "$path/$unknown/team", 409,
                mapOf("teamId" to teamIds[0], "expectedAttributionRevision" to 1), organizer, "patch-disabled")
            assertEquals("ANNOUNCEMENT_WRITE_DISABLED", off["code"].asText())
            for ((index, id, seconds) in listOf(Triple(0, known, 1L), Triple(1, unknown, 4L))) {
                val startBody = mapOf("id" to id, "teamId" to teamIds[0].takeIf { id == known }, "startedAt" to at.plusSeconds(seconds))
                val endBody = mapOf("endedAt" to at.plusSeconds(seconds + 2))
                assertEquals(corrected[index], http.call("POST", path, 200, startBody, organizer, "off-start-$index"))
                assertEquals(corrected[index], http.call("POST", "$path/$id/end", 200, endBody, organizer, "off-end-$index"))
            }
            assertEquals(corrected, http.call("GET", path, 200, evidence = "list-disabled"))
            val export = http.call("POST", "/api/v1/matches/export", 200, mapOf("matchIds" to listOf(matchId)), admin, "export-disabled")
            assertEquals(2, export["formatVersion"].asInt())
            assertTrue(export["matches"][0]["announcements"].all { it["teamId"].asText() == teamIds[1].toString() })
        }
    }

    private fun start(enabled: Boolean, target: String = "latest"): ServletWebServerApplicationContext {
        val url = System.getenv("TEST_POSTGRES_URL")
        val separator = if ('?' in url) '&' else '?'
        return SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.secure-origin=$origin",
            "--spring.datasource.url=$url${separator}currentSchema=$schema", "--spring.flyway.schemas=$schema",
            "--spring.flyway.target=$target", "--ultistats.announcements.write-enabled=$enabled",
        ) as ServletWebServerApplicationContext
    }

    private class Client(app: ServletWebServerApplicationContext) {
        private val url = "http://127.0.0.1:${app.webServer.port}"
        private val http = HttpClient.newHttpClient()
        private val mapper = app.getBean(ObjectMapper::class.java)

        fun call(method: String, path: String, status: Int, body: Any? = null, login: AuthLoginResult? = null,
            evidence: String? = null, csrf: Boolean = true): JsonNode {
            val request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15))
            if (path.startsWith("/api/")) request.header("Origin", origin)
            val cookies = mutableListOf<String>()
            if (login != null) {
                val context = login.context
                request.header("X-UltiStats-Installation-Id", context.installationId.toString())
                    .header("X-UltiStats-Account-Id", context.account!!.id.toString())
                    .header("X-UltiStats-Session-Id", context.sessionId.toString())
                cookies += "ULTISTATS_LOOPBACK_SESSION=${login.credential}"
            }
            if (body != null && login != null && csrf) {
                val token = http.send(HttpRequest.newBuilder(URI.create("$url/api/v1/auth/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(200, token.statusCode())
                cookies += token.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
                val payload = mapper.readTree(token.body())
                request.header(payload["headerName"].asText(), payload["token"].asText())
            }
            if (cookies.isNotEmpty()) request.header("Cookie", cookies.joinToString("; "))
            if (body != null) request.header("Content-Type", "application/json")
            val response = http.send(request.method(method, body?.let {
                HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it))
            } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
            val proof = System.getenv("ANNOUNCEMENT_ATTRIBUTION_PROOF_DIR")
            if (proof != null && evidence != null) {
                val directory = Files.createDirectories(Path.of(proof))
                Files.writeString(directory.resolve("$evidence.json"), response.body())
                val headers = response.headers().map().filterKeys { !it.equals("set-cookie", ignoreCase = true) }
                Files.writeString(directory.resolve("$evidence.headers"), "${response.statusCode()} $headers\n")
            }
            assertEquals(status, response.statusCode(), response.body())
            if (path.startsWith("/api/") && status != 401) {
                assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
            }
            return mapper.readTree(response.body())
        }
    }

    private companion object {
        const val origin = "http://localhost:8080"
    }
}
