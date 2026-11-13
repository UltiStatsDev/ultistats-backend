package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.principal
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.service.AuthService
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:obs-keys;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ObsSourceKeysIntegrationTest.Config::class)
class ObsSourceKeysIntegrationTest {
    companion object {
        private val fixtureKey = ByteArray(32) { (it + 1).toByte() }
        private val fixtureFile = Files.createTempFile("obs-rotation-test", ".bin").apply {
            toFile().deleteOnExit()
            Files.write(this, fixtureKey)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.obs.source-key-file") { fixtureFile.toString() }
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary
        fun testConnector(): AuthEntryClassifier = object : AuthEntryClassifier {
            override fun classify(request: jakarta.servlet.http.HttpServletRequest): AuthEntryMode =
                AuthEntryMode.valueOf(request.getAttribute("testConnector")?.toString() ?: "SECURE")
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var events: SpringDataEventRepository
    @Autowired private lateinit var announcements: MatchAnnouncementRepository

    private val keys = "/api/v1/obs/source-keys"
    private fun source(matchId: UUID) = "/api/v1/obs/sources/scoreboard/$matchId"

    @Test
    fun `exact scoreboard exposes committed pauses while public scoreboard keeps its shape`() {
        val match = UUID.randomUUID()
        val team = UUID.randomUUID()
        val timeout = UUID.randomUUID()
        val announcement = UUID.randomUUID()
        val startedAt = Instant.parse("2026-01-01T00:00:00Z")
        val timeoutAt = startedAt.plusSeconds(1)
        val announcementAt = timeoutAt.plusNanos(123)
        jdbc.update("INSERT INTO matches(id, players_per_team) VALUES (?, 7)", match)
        jdbc.update("INSERT INTO teams(id, name) VALUES (?, 'Fixture')", team)
        jdbc.update(
            "INSERT INTO match_teams(match_id, team_id, position, score, team_name) VALUES (?, ?, 1, 3, 'Fixture')",
            match, team,
        )
        val secret = "A".repeat(43)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
        jdbc.update(
            "INSERT INTO obs_source_keys(key_id, match_id, scope, secret_digest, created_at) VALUES (?, ?, 'EXACT_SCOREBOARD', ?, CURRENT_TIMESTAMP)",
            UUID.randomUUID(), match, digest,
        )

        fun snapshot(): JsonNode {
            val response = mvc.perform(request(HttpMethod.GET, source(match)).header("Authorization", "Bearer $secret"))
                .andReturn().response
            assertEquals(200, response.status)
            return json.readTree(response.contentAsString)
        }

        val planned = snapshot()
        assertEquals("PLANNED", planned["status"].asText())
        assertEquals(json.createArrayNode(), planned["activePauses"])

        jdbc.update("UPDATE matches SET started_at = ? WHERE id = ?", java.sql.Timestamp.from(startedAt), match)
        events.save(EventEntity(
            timeout, match, 1, EventType.TIMEOUT_START, timeoutAt,
            teamId = team,
        ))
        announcements.insert(match, MatchAnnouncement(announcement, null, announcementAt))
        val paused = snapshot()
        assertEquals("IN_PROGRESS", paused["status"].asText())
        assertEquals(3, paused["teams"][0]["score"].asInt())
        assertEquals(json.readTree("""
            [
              {"id":"$timeout","type":"TIMEOUT","startedAt":"$timeoutAt"},
              {"id":"$announcement","type":"ANNOUNCEMENT","startedAt":"$announcementAt"}
            ]
        """.trimIndent()), paused["activePauses"])

        val public = mvc.perform(request(HttpMethod.GET, "/api/v1/obs/scoreboard/$match")).andReturn().response
        assertEquals(200, public.status)
        assertEquals(planned.fieldNames().asSequence().toSet() - "activePauses",
            json.readTree(public.contentAsString).fieldNames().asSequence().toSet())

        jdbc.update("UPDATE matches SET ended_at = ? WHERE id = ?", java.sql.Timestamp.from(startedAt.plusSeconds(2)), match)
        jdbc.update("UPDATE match_teams SET score = 4 WHERE match_id = ?", match)
        val finished = snapshot()
        assertEquals("FINISHED", finished["status"].asText())
        assertEquals(4, finished["teams"][0]["score"].asInt())
        assertEquals(json.createArrayNode(), finished["activePauses"])
    }

    @Test
    fun `only an active exact scoreboard key authorizes the bound scoreboard`() {
        ResourceDatabasePopulator(ClassPathResource("db/migration/V11__accounts_and_sessions.sql")).execute(dataSource)
        val match = UUID.randomUUID()
        val other = UUID.randomUUID()
        jdbc.update("INSERT INTO matches(id, players_per_team) VALUES (?, 7), (?, 7)", match, other)
        auth.ownerBootstrap("obs-owner", "OBS Owner", "synthetic-temporary")
        val initial = auth.login("obs-owner", "synthetic-temporary", null)
        val account = initial.context.account!!.id
        val installation = initial.context.installationId
        val login = auth.changePassword(
            initial.context.principal(),
            "synthetic-temporary", "synthetic-permanent",
        )
        val staff = Cookie("__Host-ULTISTATS_SESSION", login.credential)
        val csrf = mvc.perform(request(HttpMethod.GET, "/api/v1/auth/csrf").cookie(staff)).andReturn().response
        val csrfCookie = csrf.getCookie("__Host-ULTISTATS_CSRF")!!
        val csrfToken = json.readTree(csrf.contentAsString)["token"].asText()

        fun mutation(
            method: HttpMethod, path: String, withCsrf: Boolean = true, withIdentity: Boolean = true,
            operation: UUID = UUID.randomUUID(), matchId: UUID = match,
        ): MockHttpServletRequestBuilder =
            request(method, path).cookie(staff, csrfCookie).apply {
                if (withIdentity) {
                    header("X-UltiStats-Installation-Id", installation)
                    header("X-UltiStats-Account-Id", account)
                    header("X-UltiStats-Session-Id", login.context.sessionId!!)
                }
                if (withCsrf) header("X-CSRF-TOKEN", csrfToken)
                if (method == HttpMethod.POST) contentType("application/json").content("""{"matchId":"$matchId","operationId":"$operation"}""")
            }
        fun expect(builder: MockHttpServletRequestBuilder, status: Int): JsonNode {
            val response = mvc.perform(builder).andReturn().response
            assertEquals(status, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertFalse(response.containsHeader("Location"))
            return json.readTree(response.contentAsString)
        }
        expect(mutation(HttpMethod.POST, keys, withIdentity = false), 403)
        expect(mutation(HttpMethod.POST, keys, withCsrf = false), 403)
        val current = "$keys/current/$match"
        fun current() = request(HttpMethod.GET, current).cookie(staff)
        expect(request(HttpMethod.GET, current), 401)
        expect(current().requestAttr("testConnector", "LOOPBACK"), 403)
        assertEquals("NO_ACTIVE_LINK", expect(current(), 200)["status"].asText())

        fun rotate(operation: UUID = UUID.randomUUID(), status: Int = 201, matchId: UUID = match) =
            expect(mutation(HttpMethod.POST, keys, operation = operation, matchId = matchId), status)
        val firstOperation = UUID.randomUUID()
        val first = rotate(firstOperation)
        assertTrue(first == rotate(firstOperation), "Exact retry must recover the original response")
        assertTrue(first == expect(current().header("Host", "untrusted.example"), 200), "Request Host must not affect the URL")
        val token = first["url"].asText().substringAfter("#key=")
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(first["url"].asText() == "https://localhost:8443/obs-sources/scoreboard.html?matchId=$match#key=$token")
        assertEquals("EXACT_SCOREBOARD", first["key"]["scope"].asText())
        assertEquals("OBS_SOURCE_OPERATION_CONFLICT", rotate(firstOperation, 409, other)["code"].asText())

        fun read(secret: String, id: UUID = match) = request(HttpMethod.GET, source(id)).header("Authorization", "Bearer $secret")
        val missing = mvc.perform(request(HttpMethod.GET, source(match)).cookie(staff)).andReturn().response
        assertEquals(401, missing.status)
        assertEquals("Bearer", missing.getHeader("WWW-Authenticate"))
        assertEquals("no-store", missing.getHeader("Cache-Control"))
        assertEquals(json.readTree(missing.contentAsString), expect(read("malformed"), 401))
        assertEquals(json.readTree(missing.contentAsString), expect(read("B".repeat(43)), 401))
        expect(read(token).header("Authorization", "Bearer " + "B".repeat(43)), 401)
        expect(read(token, other), 403)
        expect(read(token).requestAttr("testConnector", "ANONYMOUS").header("X-Forwarded-Proto", "https"), 403)
        expect(read(token).requestAttr("testConnector", "LOOPBACK"), 403)
        expect(request(HttpMethod.GET, keys).header("Authorization", "Bearer $token"), 401)
        expect(request(HttpMethod.HEAD, source(match)).header("Authorization", "Bearer $token"), 403)
        assertEquals("PLANNED", expect(read(token), 200)["status"].asText())

        try {
            Files.write(fixtureFile, ByteArray(32) { 33 })
            assertEquals("OBS_SOURCE_KEY_MATERIAL_UNAVAILABLE", expect(current(), 503)["code"].asText())
            rotate(status = 503)
            Files.delete(fixtureFile)
            expect(current(), 503)
            rotate(status = 503)
            expect(read(token), 200)
        } finally {
            Files.write(fixtureFile, fixtureKey)
        }
        assertTrue(first == expect(current(), 200), "Restoring the key file must recover the original response")
        val secondOperation = UUID.randomUUID()
        val second = rotate(secondOperation)
        val secondToken = second["url"].asText().substringAfter("#key=")
        assertTrue(token != secondToken)
        assertEquals("ACTIVE", second["status"].asText())
        rotate(firstOperation, 409)
        val metadata = expect(request(HttpMethod.GET, keys).cookie(staff), 200).filter { it["matchId"].asText() == match.toString() }
        assertEquals(2, metadata.size)
        assertTrue(metadata.all {
            it.fieldNames().asSequence().toSet() - "revokedAt" == setOf("keyId", "matchId", "scope", "createdAt")
        })

        val revoke = "$keys/${first["key"]["keyId"].asText()}"
        expect(mutation(HttpMethod.DELETE, revoke, withCsrf = false), 403)
        val revokedAt = metadata.first { it["keyId"] == first["key"]["keyId"] }["revokedAt"]
        assertTrue(revokedAt.isTextual)
        repeat(2) { expect(mutation(HttpMethod.DELETE, revoke), 204) }
        assertEquals(revokedAt, expect(request(HttpMethod.GET, keys).cookie(staff), 200)
            .first { it["keyId"] == first["key"]["keyId"] }["revokedAt"])
        assertEquals(json.readTree(missing.contentAsString), expect(read(token), 401))
        assertEquals(match.toString(), expect(read(secondToken), 200)["matchId"].asText())
        val thirdOperation = UUID.randomUUID()
        val third = rotate(thirdOperation, matchId = other)
        expect(mutation(HttpMethod.DELETE, "$keys/${third["key"]["keyId"].asText()}"), 204)
        rotate(thirdOperation, 409, other)
        assertTrue(second == expect(current(), 200), "Revoking another scope must not change the current link")
        jdbc.update("UPDATE matches SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", match)
        expect(read(secondToken), 404)
        expect(mutation(HttpMethod.POST, keys), 404)
    }
}
