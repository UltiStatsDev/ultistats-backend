package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.auth.security.AuthRequirement
import com.github.mihanizzm.ultistats.auth.security.AuthSurfacePolicyImpl
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.service.MatchService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class AuthSurfacePolicyTest {
    private val matches = Mockito.mock(MatchService::class.java)
    private val policy = AuthSurfacePolicyImpl(matches)
    private val matchId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private fun requirement(method: String, path: String, vararg preview: String): AuthRequirement {
        val request = MockHttpServletRequest(method, path)
        if (preview.isNotEmpty()) request.addParameter("preview", *preview)
        return policy.requirement(request)
    }

    // Removing any legacy or receipt rule must fail a real request classification.
    @TestFactory
    fun `all reviewed application method path mappings have explicit policy`(): List<DynamicTest> {
        val inventory = jacksonObjectMapper().readTree(Path.of("docs/api/auth-surfaces-v1.json").toFile())
        return inventory["routes"].flatMap { row ->
            val method = row["method"].asText()
            val path = row["path"].asText().replace(Regex("\\{[^}]+}"), matchId.toString())
            val expected = when (val name = row["policy"].asText()) {
                "PUBLIC" -> AuthRequirement.PublicRead
                "FINISHED_OR_STATISTICS_READ_ALL" -> AuthRequirement.Permission(AuthPermission.STATISTICS_READ_ALL)
                else -> AuthRequirement.Permission(AuthPermission.valueOf(name))
            }
            (if (method == "GET") listOf("GET", "HEAD") else listOf(method)).map { verb ->
                DynamicTest.dynamicTest("$verb $path") { assertEquals(expected, requirement(verb, path)) }
            }
        }
    }

    @TestFactory
    fun `all packaged static assets use reviewed public or staff policy`(): List<DynamicTest> {
        val inventory = jacksonObjectMapper().readTree(Path.of("docs/api/auth-surfaces-v1.json").toFile())
        return inventory["static"].flatMap { row ->
            val expected = if (row["policy"].asText() == "PUBLIC") AuthRequirement.PublicRead
                else AuthRequirement.Permission(AuthPermission.valueOf(row["policy"].asText()))
            listOf("GET", "HEAD").map { verb ->
                DynamicTest.dynamicTest("$verb ${row["path"].asText()}") {
                    assertEquals(expected, requirement(verb, row["path"].asText()))
                }
            }
        }
    }

    @Test
    fun `statistics JSON and ZIP are public only for existing finished match`() {
        for (suffix in listOf("", "/export")) for (verb in listOf("GET", "HEAD")) {
            val path = "/api/v1/matches/$matchId/statistics$suffix"
            for (match in listOf(null, Match(matchId, emptyList()), Match(matchId, emptyList(), startedAt = Instant.EPOCH))) {
                Mockito.`when`(matches.get(matchId)).thenReturn(match)
                assertEquals(AuthRequirement.Permission(AuthPermission.STATISTICS_READ_ALL), requirement(verb, path))
            }
            Mockito.`when`(matches.get(matchId)).thenReturn(Match(matchId, emptyList(), endedAt = Instant.EPOCH))
            assertEquals(AuthRequirement.PublicRead, requirement(verb, path))
        }
    }

    @Test
    fun `source pages public but any preview one requires OBS admin`() {
        for (path in listOf("/obs-battle-log.html", "/obs-scoreboard.html")) {
            assertEquals(AuthRequirement.PublicRead, requirement("GET", path))
            assertEquals(AuthRequirement.Permission(AuthPermission.OBS_ADMIN), requirement("GET", path, "1"))
            assertEquals(AuthRequirement.Permission(AuthPermission.OBS_ADMIN), requirement("HEAD", path, "0", "1"))
        }
    }

    @Test
    fun `docs aliases YAML config and generated assets require API docs permission`() {
        for (path in listOf("/swagger-ui.html", "/swagger-ui/index.html", "/swagger-ui/swagger-initializer.js", "/v3/api-docs", "/v3/api-docs/swagger-config", "/v3/api-docs.yaml"))
            for (verb in listOf("GET", "HEAD")) assertEquals(AuthRequirement.Permission(AuthPermission.API_DOCS), requirement(verb, path))
    }

    @Test
    fun `unknown methods paths and deceptive aliases never get public grant`() {
        for (path in listOf("/api/v1/new-private-api", "/api/v1/matches/x/statistics/unknown", "/api/v1/matches/", "/api/v1/Teams", "/api/v1/teams;x=1", "/api/v1/teams%2fprivate", "/uploads/../secret", "/error", "/unknown"))
            assertEquals(AuthRequirement.Deny, requirement("GET", path))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "TRACE", "CONNECT"))
            for (path in listOf("/uploads/photo.png", "/obs-scoreboard.html", "/swagger-ui/index.html", "/api/v1/obs/stream"))
                assertEquals(AuthRequirement.Deny, requirement(method, path))
    }

    @Test
    fun `effective OBS data and streams stay public while editors are private`() {
        for (path in listOf("/api/v1/obs/config", "/api/v1/obs/config/stream", "/api/v1/obs/stream", "/api/v1/obs/scoreboard/$matchId", "/uploads/photo.png"))
            assertEquals(AuthRequirement.PublicRead, requirement("GET", path))
        for (path in listOf("/obs-admin.html", "/obs/admin.mjs", "/obs/scoreboard-admin.mjs"))
            assertEquals(AuthRequirement.Permission(AuthPermission.OBS_ADMIN), requirement("GET", path))
    }
}
