package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class ObsScoreboardIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var teams: TeamService
    @Autowired lateinit var matches: MatchService
    @Autowired lateinit var transaction: TransactionTemplate
    lateinit var first: UUID
    lateinit var second: UUID
    lateinit var match: UUID
    lateinit var other: UUID
    private fun url(id: UUID = match) = "/api/v1/obs/scoreboard/$id"
    private fun profile(name: String = "Эфир") = """{"shortNames":{"$first":"$name"},"teamColors":{"$first":"#ffffff"},"swapped":true}"""
    private fun save(body: String, id: UUID = match) = mvc.perform(put("${url(id)}/profile")
        .contentType(MediaType.APPLICATION_JSON).content(body))

    @BeforeEach fun setup() {
        first = UUID.randomUUID(); second = UUID.randomUUID(); match = UUID.randomUUID(); other = UUID.randomUUID()
        teams.create(Team(first, "Длинная кириллическая команда", photoUrl = "/uploads/logo.png"))
        teams.create(Team(second, "Вторая"))
        matches.create(Match(match, listOf(first, second), playersPerTeam = 2))
        matches.create(Match(other, listOf(first, second), playersPerTeam = 2))
    }

    @Test fun `canonical snapshot uses persisted actual lifecycle and accepted score without writing anything`() {
        mvc.perform(get(url())).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("PLANNED"))
            .andExpect(jsonPath("$.teams[0].teamId").value(first.toString()))
            .andExpect(jsonPath("$.teams[0].teamName").value("Длинная кириллическая команда"))
            .andExpect(jsonPath("$.teams[0].photoUrl").value("/uploads/logo.png"))
            .andExpect(jsonPath("$.teams[0].score").value(0)).andExpect(jsonPath("$.startedAt").isEmpty)
            .andExpect(jsonPath("$.profile.swapped").value(false)).andExpect(jsonPath("$.serverNow").exists())
        jdbc.update("UPDATE matches SET started_at = ?, ended_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
            java.sql.Timestamp.from(Instant.parse("2026-01-01T01:03:12Z")), match)
        jdbc.update("UPDATE match_teams SET score = 7 WHERE match_id = ? AND team_id = ?", match, first)
        mvc.perform(get(url())).andExpect(jsonPath("$.status").value("FINISHED"))
            .andExpect(jsonPath("$.startedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.endedAt").value("2026-01-01T01:03:12Z"))
            .andExpect(jsonPath("$.teams[0].score").value(7))
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obs_scoreboard_profiles WHERE match_id = ?", Int::class.java, match)).isZero()
    }

    @Test fun `profile replacement persists independently and resets without touching games or other profiles`() {
        val before = listOf("teams", "matches", "match_teams", "events", "obs_config").associateWith { jdbc.queryForList("SELECT * FROM $it") }
        save(profile()).andExpect(status().isOk).andExpect(jsonPath("$.swapped").value(true))
        val payload = jdbc.queryForObject("SELECT payload FROM obs_scoreboard_profiles WHERE match_id = ?", String::class.java, match)!!
        assertThat(json.readTree(payload)["shortNames"][first.toString()].asText()).isEqualTo("Эфир")
        mvc.perform(get(url())).andExpect(jsonPath("$.profile.shortNames['$first']").value("Эфир"))
        mvc.perform(get(url(other))).andExpect(jsonPath("$.profile.shortNames").isEmpty).andExpect(jsonPath("$.profile.swapped").value(false))
        save("{}").andExpect(status().isOk)
        mvc.perform(get(url())).andExpect(jsonPath("$.profile.shortNames").isEmpty).andExpect(jsonPath("$.profile.teamColors").isEmpty)
            .andExpect(jsonPath("$.profile.swapped").value(false))
        before.forEach { (table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM $table")).isEqualTo(rows) }
    }

    @Test fun `invalid or unavailable match and invalid profile never write state`() {
        mvc.perform(get("/api/v1/obs/scoreboard/not-uuid")).andExpect(status().isBadRequest)
        mvc.perform(get(url(UUID.randomUUID()))).andExpect(status().isNotFound)
        save(profile(), UUID.randomUUID()).andExpect(status().isNotFound)
        val bad = listOf(
            """{"shortNames":{"$first":null}}""", """{"shortNames":{"$first":" "}}""",
            """{"shortNames":{"$first":"${"я".repeat(81)}"}}""", """{"shortNames":{"${UUID.randomUUID()}":"x"}}""",
            """{"shortNames":{"not-uuid":"x"}}""", """{"teamColors":{"$first":null}}""",
            """{"teamColors":{"$first":"red"}}""", """{"teamColors":{"${UUID.randomUUID()}":"#ffffff"}}""",
            """{"swapped":null}""", """{"shortNames":null}""", """{"teamColors":null}""",
        )
        bad.forEach { save(it).andExpect(status().isBadRequest) }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM obs_scoreboard_profiles WHERE match_id = ?", Int::class.java, match)).isZero()
        matches.delete(match)
        mvc.perform(get(url())).andExpect(status().isNotFound)
        save("{}").andExpect(status().isNotFound)
    }

    @Test fun `profile and actual start notify only after commit and rollback leaks nothing`() {
        val stream = mvc.perform(get("/api/v1/obs/stream")).andExpect(request().asyncStarted()).andReturn()
        try {
            transaction.executeWithoutResult {
                save(profile()).andExpect(status().isOk)
                matches.startMatch(match, Instant.parse("2026-01-01T00:00:00Z"))
                assertThat(stream.response.contentAsString).doesNotContain("scoreboard-profile-changed", "match-changed")
            }
            assertThat(stream.response.contentAsString).contains("event:scoreboard-profile-changed", "event:match-changed", match.toString())
            val committed = stream.response.contentAsString
            transaction.executeWithoutResult { tx ->
                save(profile("Отмена")).andExpect(status().isOk)
                matches.startMatch(other, Instant.parse("2026-01-01T00:00:00Z"))
                tx.setRollbackOnly()
            }
            assertThat(stream.response.contentAsString).isEqualTo(committed)
            mvc.perform(get(url())).andExpect(jsonPath("$.profile.shortNames['$first']").value("Эфир"))
            mvc.perform(get(url(other))).andExpect(jsonPath("$.status").value("PLANNED"))
        } finally { runCatching { stream.request.asyncContext?.complete() } }
    }
    @Test fun `accepted planned metadata change invalidates scoreboard but rejected lifecycle does not`() {
        val stream = mvc.perform(get("/api/v1/obs/stream")).andReturn()
        try {
            transaction.executeWithoutResult {
                matches.update(match, listOf(second, first), null, null)
                assertThat(stream.response.contentAsString).doesNotContain("event:match-changed")
            }
            assertThat(stream.response.contentAsString).contains("event:match-changed")
            mvc.perform(get(url())).andExpect(jsonPath("$.teams[0].teamId").value(second.toString()))
            matches.startMatch(match, Instant.parse("2026-01-01T00:00:00Z"))
            val committed = stream.response.contentAsString
            matches.startMatch(match, Instant.parse("2026-01-01T00:01:00Z"))
            matches.update(match, listOf(first, second), null, null)
            assertThat(stream.response.contentAsString).isEqualTo(committed)
        } finally { runCatching { stream.request.asyncContext?.complete() } }
    }

    @Test fun `replacing planned teams cannot expose stale team overrides in canonical profile`() {
        save(profile()).andExpect(status().isOk)
        val replacement = UUID.randomUUID()
        teams.create(Team(replacement, "Новая команда"))
        matches.update(match, listOf(replacement, second), null, null)
        mvc.perform(get(url())).andExpect(jsonPath("$.teams[0].teamId").value(replacement.toString()))
            .andExpect(jsonPath("$.profile.shortNames").isEmpty).andExpect(jsonPath("$.profile.teamColors").isEmpty)
        save(profile()).andExpect(status().isBadRequest)
        save("{}").andExpect(status().isOk)
    }

    @Test fun `concurrent initial profile saves serialize as whole documents on match row`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val gate = java.util.concurrent.CountDownLatch(1)
            val jobs = listOf("Первый", "Второй").map { name ->
                pool.submit(java.util.concurrent.Callable {
                    gate.await()
                    save(profile(name)).andExpect(status().isOk)
                })
            }
            gate.countDown();jobs.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            val persisted = jdbc.queryForList("SELECT payload FROM obs_scoreboard_profiles WHERE match_id = ?", match)
            assertThat(persisted).hasSize(1)
            assertThat(json.readTree(persisted[0]["payload"].toString())["shortNames"][first.toString()].asText())
                .isIn("Первый", "Второй")
        } finally { pool.shutdownNow() }
    }

}
