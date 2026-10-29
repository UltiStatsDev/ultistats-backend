package com.github.mihanizzm.ultistats.controller

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
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListSortingControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var teamService: TeamService
    @Autowired lateinit var playerService: PlayerService
    @Autowired lateinit var matchService: MatchService

    private lateinit var ascendingIds: Map<String, List<String>>

    @BeforeEach
    fun setUp() {
        matchService.getAll().forEach { matchService.delete(it.id) }
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
        val teams = listOf(Team(UUID.randomUUID(), "Alpha"), Team(UUID.randomUUID(), "Zulu"))
        teams.reversed().forEach { teamService.create(it) }
        val players = listOf(Player(UUID.randomUUID(), "Zulu", "Alpha"), Player(UUID.randomUUID(), "Alpha", "Zulu"))
        players.reversed().forEach { playerService.create(it) }
        val matches = (1..3).map { day ->
            Match(
                id = UUID.randomUUID(), teamIds = teams.map { it.id },
                plannedStartTimestamp = Instant.parse("2026-01-0${day}T10:00:00Z"),
                startedAt = if (day < 3) Instant.parse("2026-01-0${day}T11:00:00Z") else null,
                endedAt = if (day == 1) Instant.parse("2026-01-01T12:00:00Z") else null,
            )
        }
        matches.reversed().forEach { matchService.create(it) }
        ascendingIds = mapOf(
            "teams" to teams.map { it.id.toString() },
            "players" to players.map { it.id.toString() },
            "matches" to matches.map { it.id.toString() },
        )
    }

    @ParameterizedTest
    @CsvSource("teams,NAME,name", "players,LAST_NAME,lastName", "matches,PLANNED_START_TIMESTAMP,plannedStartTimestamp")
    fun `defaults typed directions and legacy sorting agree`(resource: String, field: String, legacyField: String) {
        val expected = ascendingIds.getValue(resource)
        mockMvc.perform(get("/api/v1/$resource"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(expected))
        mockMvc.perform(get("/api/v1/$resource").param("sortBy", field))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(expected))
        for (direction in listOf("ASC", "DESC")) {
            val ordered = if (direction == "ASC") expected else expected.reversed()
            mockMvc.perform(get("/api/v1/$resource").param("sortBy", field).param("sortDirection", direction))
                .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(ordered))
            mockMvc.perform(get("/api/v1/$resource").param("sortDirection", direction))
                .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(ordered))
            mockMvc.perform(get("/api/v1/$resource").param("sort", "$legacyField:${direction.lowercase()}"))
                .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(ordered))
        }
        mockMvc.perform(get("/api/v1/$resource").param("sort", legacyField))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(expected))
        mockMvc.perform(get("/api/v1/$resource").param("sort", " $legacyField : DeSc "))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[*].id").value(expected.reversed()))
        mockMvc.perform(get("/api/v1/$resource").param("sortBy", field).param("sortDirection", "DESC")
            .param("page", "1").param("size", "1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[0].id").value(expected.reversed()[1]))
    }

    @ParameterizedTest
    @CsvSource("players,FIRST_NAME", "matches,STARTED_AT", "matches,STATUS")
    fun `all additional supported fields sort in both directions`(resource: String, field: String) {
        val expected = ascendingIds.getValue(resource).let { if (resource == "players") it.reversed() else it }
        for (direction in listOf("ASC", "DESC")) {
            mockMvc.perform(get("/api/v1/$resource").param("sortBy", field).param("sortDirection", direction))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[*].id").value(if (direction == "ASC") expected else expected.reversed()))
        }
    }

    @Test
    fun `endedAt retains nulls last ascending and nulls first descending`() {
        val finishedId = ascendingIds.getValue("matches").first()
        mockMvc.perform(get("/api/v1/matches").param("sortBy", "ENDED_AT").param("sortDirection", "ASC"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[0].id").value(finishedId))
        mockMvc.perform(get("/api/v1/matches").param("sortBy", "ENDED_AT").param("sortDirection", "DESC"))
            .andExpect(status().isOk).andExpect(jsonPath("$.content[2].id").value(finishedId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["teams", "players", "matches"])
    fun `invalid empty duplicate and mixed sorting returns problem detail`(resource: String) {
        val legacyField = mapOf("teams" to "name", "players" to "lastName", "matches" to "plannedStartTimestamp").getValue(resource)
        val field = mapOf("teams" to "NAME", "players" to "LAST_NAME", "matches" to "PLANNED_START_TIMESTAMP").getValue(resource)
        val queries = listOf(
            mapOf("sortBy" to arrayOf("UNKNOWN")),
            mapOf("sortBy" to arrayOf("name")),
            mapOf("sortDirection" to arrayOf("sideways")),
            mapOf("sortDirection" to arrayOf("asc")),
            mapOf("sortBy" to arrayOf("")),
            mapOf("sortDirection" to arrayOf(" ")),
            mapOf("sort" to arrayOf("")),
            mapOf("sort" to arrayOf("$legacyField:sideways")),
            mapOf("sort" to arrayOf("$legacyField:")),
            mapOf("sort" to arrayOf("$legacyField:asc:desc")),
            mapOf("sort" to arrayOf("unknown:asc")),
            mapOf("sort" to arrayOf(":asc")),
            mapOf("sort" to arrayOf("number:asc")),
            mapOf("sort" to arrayOf("teamId:asc")),
            mapOf("sort" to arrayOf("$legacyField:asc"), "sortBy" to arrayOf(field)),
            mapOf("sort" to arrayOf("$legacyField:asc"), "sortBy" to arrayOf("")),
            mapOf("sort" to arrayOf("$legacyField:asc"), "sortDirection" to arrayOf("DESC")),
            mapOf("sortDirection" to arrayOf("ASC", "DESC")),
            mapOf("sortBy" to arrayOf(field, field)),
            mapOf("sort" to arrayOf(legacyField, legacyField)),
        )
        for (query in queries) {
            val request = get("/api/v1/$resource")
            query.forEach { (key, values) -> request.param(key, *values) }
            mockMvc.perform(request).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", containsString("sort")))
                .andExpect(jsonPath("$.instance").value("/api/v1/$resource"))
        }
    }

    @Test
    fun `OpenAPI describes resource enums and deprecated legacy query without a body`() {
        val document = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        val fields = mapOf(
            "teams" to listOf("NAME"),
            "players" to listOf("LAST_NAME", "FIRST_NAME"),
            "matches" to listOf("PLANNED_START_TIMESTAMP", "STARTED_AT", "ENDED_AT", "STATUS"),
        )
        for ((resource, values) in fields) {
            val operation = document["paths"]["/api/v1/$resource"]["get"]
            assertFalse(operation.has("requestBody"))
            val params = operation["parameters"].associateBy { it["name"].asText() }
            for (name in listOf("sortBy", "sortDirection", "sort")) {
                assertEquals("query", params.getValue(name)["in"].asText())
                assertFalse(params.getValue(name)["required"].asBoolean())
            }
            assertEquals(values, params.getValue("sortBy")["schema"]["enum"].map { it.asText() })
            assertEquals(values.first(), params.getValue("sortBy")["schema"]["default"].asText())
            assertEquals(listOf("ASC", "DESC"), params.getValue("sortDirection")["schema"]["enum"].map { it.asText() })
            assertEquals("ASC", params.getValue("sortDirection")["schema"]["default"].asText())
            assertEquals(true, params.getValue("sort")["deprecated"].asBoolean())
            val responseSchema = operation["responses"]["200"]["content"].elements().next()["schema"]["\$ref"].asText()
            val page = document["components"]["schemas"][responseSchema.substringAfterLast('/')]
            val itemName = mapOf("teams" to "TeamListItemResponse", "players" to "PlayerListItemResponse",
                "matches" to "MatchListItemResponse").getValue(resource)
            assertEquals("#/components/schemas/$itemName", page["properties"]["content"]["items"]["\$ref"].asText())
            assertEquals("#/components/schemas/ProblemDetail",
                operation["responses"]["400"]["content"]["application/problem+json"]["schema"]["\$ref"].asText())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["teams", "players", "matches"])
    fun `Spring binding aliases cannot bypass sorting validation`(resource: String) {
        val legacyField = mapOf("teams" to "name", "players" to "lastName", "matches" to "plannedStartTimestamp").getValue(resource)
        for (alias in listOf("sortDirection[]", "!sortDirection", "_sortDirection")) {
            mockMvc.perform(get("/api/v1/$resource").param("sort", "$legacyField:asc").param(alias, "DESC"))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }
}
