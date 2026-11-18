package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(properties = ["spring.datasource.url=jdbc:h2:mem:obs-log-config;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"])
@AutoConfigureMockMvc
@Import(ObsEventLogConfigIntegrationTest.Config::class)
class ObsEventLogConfigIntegrationTest {
    @TestConfiguration
    class Config {
        @Bean @Primary fun connector(): AuthEntryClassifier = object : AuthEntryClassifier {
            override fun classify(request: jakarta.servlet.http.HttpServletRequest) = AuthEntryMode.SECURE
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var configs: ObsConfigService

    @Test fun `exact and auto project current config only for selected teams`() {
        val match = UUID.randomUUID()
        val left = UUID.randomUUID()
        val right = UUID.randomUUID()
        val unrelated = UUID.randomUUID()
        jdbc.update("INSERT INTO matches(id, players_per_team, started_at) VALUES (?, 7, CURRENT_TIMESTAMP)", match)
        listOf(left, right).forEachIndexed { index, team ->
            jdbc.update("INSERT INTO teams(id, name) VALUES (?, 'Fixture')", team)
            jdbc.update("INSERT INTO match_teams(match_id, team_id, position, score, team_name) VALUES (?, ?, ?, 0, 'Fixture')",
                match, team, index + 1)
        }
        val exactSecret = "A".repeat(43)
        val autoSecret = "B".repeat(43)
        listOf("EXACT_EVENT_LOG" to exactSecret, "AUTO_EVENT_LOG" to autoSecret).forEach { (scope, secret) ->
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
            jdbc.update("INSERT INTO obs_source_keys(key_id, match_id, scope, secret_digest, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), if (scope.startsWith("EXACT")) match else null, scope, digest)
        }
        fun read(auto: Boolean = false): com.fasterxml.jackson.databind.JsonNode {
            val path = if (auto) "auto" else match.toString()
            val response = mvc.perform(get("/api/v1/obs/sources/event-log/$path")
                .header("Authorization", "Bearer ${if (auto) autoSecret else exactSecret}")).andReturn().response
            assertEquals(200, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            val payload = json.readTree(response.contentAsString)
            return if (auto) payload["snapshot"] else payload
        }
        jdbc.update("DELETE FROM obs_config")
        assertEquals(json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ObsConfig()), read()["config"])
        jdbc.update("INSERT INTO obs_config(id, payload) VALUES (1, '{}')")
        val custom = ObsConfig(setOf(ObsCategory.BLOCK), 3, 17, ObsCorner.BOTTOM_LEFT, "#123456", "#abcdef",
            mapOf(left to "#112233", right to "#445566", unrelated to "#778899"))
        configs.save(custom)
        val expected = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(custom.copy(teamColors = custom.teamColors - unrelated))
        assertEquals(expected, read()["config"])
        assertEquals(expected, read(true)["config"])
        configs.save(custom.copy(ttlSeconds = 25))
        assertEquals(25, read()["config"]["ttlSeconds"].asInt())
        assertEquals(25, read(true)["config"]["ttlSeconds"].asInt())
        jdbc.update("UPDATE matches SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", match)
        assertEquals(json.nodeFactory.nullNode(), read(true))
    }
}
