package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.*
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.SchemaValidatorsConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Import(LegacyDomainHttpSecurity::class)
@SpringBootTest
@AutoConfigureMockMvc
class NullableOpenApiContractTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var matches: MatchService
    @Autowired lateinit var teams: TeamService
    @Autowired lateinit var players: PlayerService
    @Autowired lateinit var roster: TeamPlayerService
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    private lateinit var document: JsonNode
    private lateinit var team: Team
    private lateinit var other: Team
    private lateinit var player: Player
    private lateinit var match: Match

    @BeforeEach
    fun setup() {
        matches.getAll().forEach { matches.delete(it.id) }
        teams.getAll().forEach { teams.delete(it.id) }
        players.getAll().forEach { players.delete(it.id) }
        team = Team(UUID.randomUUID(), "Home", "City").also { teams.create(it) }
        other = Team(UUID.randomUUID(), "Away").also { teams.create(it) }
        player = Player(UUID.randomUUID(), "First", "Last").also { players.create(it) }
        roster.add(team.id, player.id)
        match = Match(UUID.randomUUID(), listOf(team.id, other.id), mutableListOf(), playersPerTeam = 2).also { matches.create(it) }
        document = request(get("/v3/api-docs"), 200)
        Files.createDirectories(Path.of("build/contracts"))
        Files.writeString(Path.of("build/contracts/nullable-openapi.json"), document.toString())
    }

    @Test
    fun `ALWAYS participant responses require nullable fields for PLAYER and UNKNOWN`() {
        matches.startMatch(match.id, Instant.parse("2026-01-01T00:00:00Z"))
        val response = request(get("/api/v1/matches/${match.id}"), 200)
        val statistics = request(get("/api/v1/matches/${match.id}/statistics"), 200)
        listOf(
            Triple(response, "MatchParticipantResponse", listOf("unknownSlot", "firstName", "lastName", "number", "photoUrl")),
            Triple(statistics, "ParticipantStatisticsResponse", listOf("unknownSlot", "firstName", "lastName", "number")),
        ).forEach { (body, schema, fields) ->
            val participants = body.path("teams").flatMap { it.path("participants").toList() }
            assertThat(participants.map { it.path("kind").asText() }).contains("PLAYER", "UNKNOWN")
            participants.forEach { participant ->
                fields.forEach { assertThat(participant.has(it)).describedAs("$schema.$it present").isTrue() }
                assertValid(schema, participant)
            }
            fields.forEach { field ->
                assertThat(schema(schema).path("required").map { it.asText() }).contains(field)
                val missing = participants.first().deepCopy<ObjectNode>().also { it.remove(field) }
                assertThat(errors(schema, missing)).describedAs("$schema.$field must be present").isNotEmpty()
            }
        }
    }

    @Test
    fun `PATCH accepts omission and values while clearable fields also accept null`() {
        paired("PatchPlayerRequest", patch("/api/v1/players/${player.id}"), """{"firstName":"Changed"}""")
        assertThat(players.get(player.id)!!.lastName).isEqualTo("Last")
        paired("PatchPlayerRequest", patch("/api/v1/players/${player.id}"), """{"lastName":"Changed"}""")
        paired("PatchTeamRequest", patch("/api/v1/teams/${team.id}"), """{"name":"Renamed"}""")
        assertThat(teams.get(team.id)!!.city).isEqualTo("City")
        paired("PatchTeamRequest", patch("/api/v1/teams/${team.id}"), """{"city":null}""")
        assertThat(teams.get(team.id)!!.city).isNull()
        paired("PatchTeamRequest", patch("/api/v1/teams/${team.id}"), """{"city":"New city"}""")
        paired("PatchMatchRequest", patch("/api/v1/matches/${match.id}"), """{"playersPerTeam":3}""")
        paired("PatchMatchRequest", patch("/api/v1/matches/${match.id}"), """{"teamIds":["${other.id}","${team.id}"]}""")
        paired("PatchMatchRequest", patch("/api/v1/matches/${match.id}"), """{"plannedStartTimestamp":"2026-01-01T00:00:00Z"}""")
        paired("PatchMatchRequest", patch("/api/v1/matches/${match.id}"), """{"plannedStartTimestamp":null}""")
        assertThat(matches.get(match.id)!!.plannedStartTimestamp).isNull()
    }

    @Test
    fun `PATCH explicit null for domain fields is rejected by runtime and schema`() {
        listOf(
            Triple("PatchPlayerRequest", "/api/v1/players/${player.id}", listOf("firstName", "lastName")),
            Triple("PatchTeamRequest", "/api/v1/teams/${team.id}", listOf("name")),
            Triple("PatchMatchRequest", "/api/v1/matches/${match.id}", listOf("teamIds", "playersPerTeam")),
        ).forEach { (schema, path, fields) -> fields.forEach { field ->
            val json = """{"$field":null}"""
            request(patch(path), 400, json)
            assertThat(errors(schema, mapper.readTree(json))).describedAs("$schema.$field rejects null").isNotEmpty()
        } }
    }

    @Test
    fun `nullable create legacy update and jersey clearing match raw schema`() {
        paired("CreateTeamRequest", post("/api/v1/teams"), """{"name":"New","city":null}""", 201)
        paired("CreateMatchRequest", post("/api/v1/matches"), """{"teamIds":["${team.id}","${other.id}"],"playersPerTeam":2,"plannedStartTimestamp":null}""", 201)
        paired("UpdatePlayerRequest", put("/api/v1/players/${player.id}"), """{"firstName":null,"lastName":null}""")
        paired("UpdateTeamRequest", put("/api/v1/teams/${team.id}"), """{"name":null,"city":null}""")
        paired("UpdateMatchRequest", put("/api/v1/matches/${match.id}"), """{"teamIds":null,"playersPerTeam":null,"plannedStartTimestamp":null}""")
        val path = "/api/v1/teams/${team.id}/players/${player.id}"
        paired("UpsertTeamPlayerRequest", put(path), """{"number":7}""")
        paired("UpsertTeamPlayerRequest", put(path), """{"number":null}""")
        assertThat(roster.get(team.id, player.id)!!.number).isNull()
        listOf("CreateTeamRequest" to "name", "CreateMatchRequest" to "teamIds", "CreateMatchRequest" to "playersPerTeam").forEach { (name, field) ->
            assertThat(schema(name).path("required").map { it.asText() }).contains(field)
            assertThat(errors(name, mapper.readTree("""{"$field":null}"""))).isNotEmpty()
        }
    }

    @Test
    fun `scoreboard lifecycle timestamps are required nullable in raw schema for every status`() {
        val path = "/api/v1/obs/scoreboard/${match.id}"
        val planned = request(get(path), 200)
        matches.startMatch(match.id, Instant.parse("2026-01-01T00:00:00Z"))
        val live = request(get(path), 200)
        jdbc.update("UPDATE matches SET ended_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.parse("2026-01-01T01:03:12Z")), match.id)
        val finished = request(get(path), 200)
        assertThat(planned.path("status").asText()).isEqualTo("PLANNED")
        assertThat(live.path("status").asText()).isEqualTo("IN_PROGRESS")
        assertThat(finished.path("status").asText()).isEqualTo("FINISHED")
        listOf(planned, live, finished).forEach { body ->
            assertValid("ObsScoreboardSnapshot", body)
            listOf("startedAt", "endedAt").forEach { field ->
                assertThat(body.has(field)).isTrue()
                assertThat(schema("ObsScoreboardSnapshot").path("required").map { it.asText() }).contains(field)
                val omitted = body.deepCopy<ObjectNode>().also { it.remove(field) }
                assertThat(errors("ObsScoreboardSnapshot", omitted)).isNotEmpty()
            }
        }
        assertThat(planned.path("startedAt").isNull).isTrue()
        assertThat(live.path("endedAt").isNull).isTrue()
    }

    private fun paired(schema: String, builder: MockHttpServletRequestBuilder, json: String, status: Int = 200) {
        request(builder, status, json)
        assertValid(schema, mapper.readTree(json))
    }

    private fun request(builder: MockHttpServletRequestBuilder, status: Int, json: String? = null): JsonNode {
        if (json != null) builder.contentType(MediaType.APPLICATION_JSON).content(json)
        val response = mvc.perform(builder).andReturn().response
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(status)
        return if (response.contentAsString.isBlank()) mapper.nullNode() else mapper.readTree(response.contentAsString)
    }

    private fun schema(name: String) = document.path("components").path("schemas").path(name)

    private fun errors(name: String, value: JsonNode): Set<com.networknt.schema.ValidationMessage> {
        // Keep the raw component and its references intact; no consumer normalization.
        val root = mapper.createObjectNode()
        root.put("$" + "schema", "https://json-schema.org/draft/2020-12/schema")
        root.put("$" + "ref", "#/components/schemas/$name")
        root.set<JsonNode>("components", document.path("components"))
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()).validate(value)
    }

    private fun assertValid(name: String, value: JsonNode) {
        assertThat(errors(name, value)).describedAs("$name: $value").isEmpty()
    }
}
