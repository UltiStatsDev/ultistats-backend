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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:obs-keys;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ObsSourceKeysIntegrationTest.Config::class)
class ObsSourceKeysIntegrationTest {
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
    @Autowired private lateinit var sourceKeys: ObsSourceKeyService
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
        val secret = sourceKeys.issue(match).secret

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

        fun mutation(method: HttpMethod, path: String, withCsrf: Boolean = true, withIdentity: Boolean = true): MockHttpServletRequestBuilder =
            request(method, path).cookie(staff, csrfCookie).apply {
                if (withIdentity) {
                    header("X-UltiStats-Installation-Id", installation)
                    header("X-UltiStats-Account-Id", account)
                    header("X-UltiStats-Session-Id", login.context.sessionId!!)
                }
                if (withCsrf) header("X-CSRF-TOKEN", csrfToken)
                if (method == HttpMethod.POST) contentType("application/json").content("""{"matchId":"$match"}""")
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
        val first = expect(mutation(HttpMethod.POST, keys), 201)
        val second = expect(mutation(HttpMethod.POST, keys), 201)
        val token = first["secret"].asText()
        val secondToken = second["secret"].asText()
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")) && token != secondToken)
        assertEquals("EXACT_SCOREBOARD", first["scope"].asText())
        val metadata = expect(request(HttpMethod.GET, keys).cookie(staff), 200)
        assertEquals(2, metadata.size())
        assertTrue(metadata.all { !it.has("secret") && !it.has("secretDigest") && it["matchId"].asText() == match.toString() })

        fun read(secret: String, id: UUID = match) = request(HttpMethod.GET, source(id)).header("Authorization", "Bearer $secret")
        val missing = mvc.perform(request(HttpMethod.GET, source(match)).cookie(staff)).andReturn().response
        assertEquals(401, missing.status)
        assertEquals("Bearer", missing.getHeader("WWW-Authenticate"))
        assertEquals("no-store", missing.getHeader("Cache-Control"))
        assertEquals(json.readTree(missing.contentAsString), expect(read("malformed"), 401))
        assertEquals(json.readTree(missing.contentAsString), expect(read("A".repeat(43)), 401))
        expect(read(token).header("Authorization", "Bearer $secondToken"), 401)
        expect(read(token, other), 403)
        expect(read(token).requestAttr("testConnector", "ANONYMOUS").header("X-Forwarded-Proto", "https"), 403)
        expect(read(token).requestAttr("testConnector", "LOOPBACK"), 403)
        expect(request(HttpMethod.GET, keys).header("Authorization", "Bearer $token"), 401)
        expect(request(HttpMethod.HEAD, source(match)).header("Authorization", "Bearer $token"), 403)
        assertEquals("PLANNED", expect(read(token), 200)["status"].asText())
        assertEquals(match.toString(), expect(read(secondToken), 200)["matchId"].asText())

        val revoke = "$keys/${first["keyId"].asText()}"
        expect(mutation(HttpMethod.DELETE, revoke, withCsrf = false), 403)
        expect(mutation(HttpMethod.DELETE, revoke), 204)
        val revokedAt = expect(request(HttpMethod.GET, keys).cookie(staff), 200)
            .first { it["keyId"] == first["keyId"] }["revokedAt"]
        assertTrue(revokedAt.isTextual)
        expect(mutation(HttpMethod.DELETE, revoke), 204)
        assertEquals(revokedAt, expect(request(HttpMethod.GET, keys).cookie(staff), 200)
            .first { it["keyId"] == first["keyId"] }["revokedAt"])
        assertEquals(json.readTree(missing.contentAsString), expect(read(token), 401))
        expect(read(secondToken), 200)
        jdbc.update("UPDATE matches SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", match)
        expect(read(secondToken), 404)
        expect(mutation(HttpMethod.POST, keys), 404)
    }
}
