package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@Import(LegacyDomainHttpSecurity::class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListQueryValidationControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var teamService: TeamService
    @Autowired lateinit var playerService: PlayerService
    @Autowired lateinit var matchService: MatchService

    private lateinit var teamId: UUID
    private lateinit var matchId: UUID

    @BeforeEach
    fun setUp() {
        matchService.getAll().forEach { matchService.delete(it.id) }
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
        val teams = listOf(Team(UUID.randomUUID(), "Alpha"), Team(UUID.randomUUID(), "Beta"))
        teams.forEach { teamService.create(it) }
        teamId = teams.first().id
        playerService.create(Player(UUID.randomUUID(), "Alice", "Alpha"))
        matchId = UUID.randomUUID()
        matchService.create(Match(matchId, teams.map { it.id }, plannedStartTimestamp = Instant.parse("2026-01-02T12:00:00Z")))
    }

    @ParameterizedTest
    @CsvSource("page,-1", "page,2147483648", "page,abc", "page,1.5", "size,0", "size,-1", "size,101", "size,2147483648", "size,abc")
    fun `invalid pagination returns a parameter specific problem`(name: String, value: String) {
        for (resource in listOf("teams", "players", "matches")) {
            assertInvalid(resource, name, value)
        }
    }

    @ParameterizedTest
    @CsvSource("teams,page", "teams,size", "teams,name", "players,page", "players,size", "players,name", "players,teamId", "matches,page", "matches,size", "matches,teamId", "matches,status", "matches,dateFrom", "matches,dateTo")
    fun `empty and repeated scalar parameters are rejected`(resource: String, name: String) {
        assertInvalid(resource, name, "")
        assertInvalid(resource, name, " ")
        val value = when (name) {
            "page" -> "0"
            "size" -> "20"
            "teamId" -> teamId.toString()
            "status" -> "PLANNED"
            "dateFrom", "dateTo" -> "2026-01-02T12:00:00Z"
            else -> "Alpha"
        }
        assertInvalid(resource, name, value, value)
        for (alias in listOf("!$name", "_$name", "$name[]", "$name[0]")) {
            assertInvalid(resource, alias, value, detailField = name)
        }
    }

    @ParameterizedTest
    @CsvSource("players,teamId,not-a-uuid", "matches,teamId,not-a-uuid", "matches,status,UNKNOWN", "matches,status,planned", "matches,dateFrom,not-a-date", "matches,dateTo,2026-01-01")
    fun `invalid typed filters use the same error contract`(resource: String, name: String, value: String) {
        assertInvalid(resource, name, value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["teams", "players", "matches"])
    fun `pagination defaults boundaries and far out pages remain valid`(resource: String) {
        mockMvc.perform(get("/api/v1/$resource"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentPage").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.content").isNotEmpty)
        for (size in listOf("1", "100")) {
            mockMvc.perform(get("/api/v1/$resource").param("size", size))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.size").value(size.toInt()))
                .andExpect(jsonPath("$.content").isNotEmpty)
        }
        // The old Int multiplication either wraps to zero or becomes negative.
        for ((page, size) in listOf("1073741824" to "4", "2147483647" to "20")) {
            mockMvc.perform(get("/api/v1/$resource").param("page", page).param("size", size))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.currentPage").value(page.toInt()))
                .andExpect(jsonPath("$.content").isEmpty)
                .andExpect(jsonPath("$.totalElements").value(if (resource == "teams") 2 else 1))
        }
    }

    @Test
    fun `reversed dates are rejected`() {
        mockMvc.perform(get("/api/v1/matches")
            .param("dateFrom", "2026-01-03T12:00:00Z").param("dateTo", "2026-01-01T12:00:00Z"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.detail", containsString("dateFrom")))
            .andExpect(jsonPath("$.detail", containsString("dateTo")))
    }

    @Test
    fun `inclusive and one sided ranges filter by planned start`() {
        for (parameters in listOf(
            mapOf("dateFrom" to "2026-01-02T12:00:00Z"),
            mapOf("dateTo" to "2026-01-02T12:00:00Z"),
            mapOf("dateFrom" to "2026-01-02T12:00:00Z", "dateTo" to "2026-01-02T12:00:00Z"),
            mapOf("dateFrom" to "2026-01-01T12:00:00Z", "dateTo" to "2026-01-03T12:00:00Z"),
        )) {
            val request = get("/api/v1/matches").param("teamId", teamId.toString()).param("status", "PLANNED")
            parameters.forEach { (name, value) -> request.param(name, value) }
            mockMvc.perform(request).andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].id").value(matchId.toString()))
        }
        mockMvc.perform(get("/api/v1/matches").param("dateFrom", "2026-01-03T12:00:00Z"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content").isEmpty)
        mockMvc.perform(get("/api/v1/matches").param("dateTo", "2026-01-01T12:00:00Z"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content").isEmpty)
    }

    @Test
    fun `date filters exclude matches without a planned start`() {
        matchService.create(Match(UUID.randomUUID(), matchService.getOrThrow(matchId).teamIds))
        mockMvc.perform(get("/api/v1/matches"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content.length()").value(2))
        for (boundary in listOf("dateFrom", "dateTo")) {
            mockMvc.perform(get("/api/v1/matches").param(boundary, "2026-01-02T12:00:00Z"))
                .andExpect(status().isOk).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(matchId.toString()))
        }
    }

    @Test
    fun `name search and an unknown team remain valid filters`() {
        for (resource in listOf("teams", "players")) {
            mockMvc.perform(get("/api/v1/$resource").param("name", "alp"))
                .andExpect(status().isOk).andExpect(jsonPath("$.content.length()").value(1))
        }
        for (resource in listOf("players", "matches")) {
            mockMvc.perform(get("/api/v1/$resource").param("teamId", UUID.randomUUID().toString()))
                .andExpect(status().isOk).andExpect(jsonPath("$.content").isEmpty)
        }
    }

    @Test
    fun `OpenAPI exposes pagination constraints and existing filter parameters`() {
        val document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        for (resource in listOf("teams", "players", "matches")) {
            val operation = document["paths"]["/api/v1/$resource"]["get"]
            val parameters = operation["parameters"].associateBy { it["name"].asText() }
            for (name in listOf("page", "size")) {
                assertEquals(false, parameters.getValue(name)["required"].asBoolean())
                assertEquals("integer", parameters.getValue(name)["schema"]["type"].asText())
            }
            assertEquals(0, parameters.getValue("page")["schema"]["minimum"].asInt())
            assertEquals(0, parameters.getValue("page")["schema"]["default"].asInt())
            assertEquals(1, parameters.getValue("size")["schema"]["minimum"].asInt())
            assertEquals(100, parameters.getValue("size")["schema"]["maximum"].asInt())
            assertEquals(20, parameters.getValue("size")["schema"]["default"].asInt())
            val expectedFilters = when (resource) {
                "matches" -> setOf("teamId", "status", "dateFrom", "dateTo")
                "players" -> setOf("name", "teamId")
                else -> setOf("name")
            }
            assertEquals(expectedFilters + setOf("page", "size", "sortBy", "sortDirection", "sort"), parameters.keys)
            assertEquals(true, operation["responses"]["400"]["content"].has("application/problem+json"))
        }
    }

    private fun assertInvalid(resource: String, name: String, vararg values: String, detailField: String = name) {
        mockMvc.perform(get("/api/v1/$resource").param(name, *values))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.detail", containsString(detailField)))
            .andExpect(jsonPath("$.instance").value("/api/v1/$resource"))
    }
}
