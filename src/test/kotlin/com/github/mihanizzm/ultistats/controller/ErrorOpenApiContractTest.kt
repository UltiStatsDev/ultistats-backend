package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(LegacyDomainHttpSecurity::class)
@SpringBootTest
@AutoConfigureMockMvc
class ErrorOpenApiContractTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var matches: MatchService
    @Autowired lateinit var teams: TeamService

    @Test
    fun `invalid page ProblemDetail validates against raw schema with format checks`() {
        val response = mvc.perform(get("/api/v1/players").param("page", "-1"))
            .andExpect(status().isBadRequest).andReturn().response
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.contentType)
        val body = mapper.readTree(response.contentAsString)
        assertEquals(400, body["status"].asInt())
        assertEquals("/api/v1/players", body["instance"].asText())
        assertValid("ProblemDetail", body)
    }

    @Test
    fun `batch errors validate real reconciliation fixtures including optional offending event fields`() {
        val one = Team(UUID.randomUUID(), "Error contract one")
        val two = Team(UUID.randomUUID(), "Error contract two")
        teams.create(one)
        teams.create(two)
        val match = Match(UUID.randomUUID(), listOf(one.id, two.id))
        matches.create(match)
        val at = Instant.parse("2026-10-31T00:00:00Z")
        matches.startMatch(match.id, at)
        val participant = matches.getOrThrow(match.id).participantsByTeam.values.first().first().participantId
        val path = "/api/v2/matches/${match.id}/events/batch"
        fun batch(events: List<Any>, sequence: Int = 0) = mapOf(
            "events" to events, "expectedLastSequenceNumber" to sequence, "expectedLogRevision" to 0,
        )
        fun pull() = mapOf("type" to "PULL", "eventId" to UUID.randomUUID(),
            "occurredAt" to at.toString(), "participantId" to participant)
        fun error(payload: Any, code: Int): JsonNode {
            val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().`is`(code)).andReturn().response
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.contentType)
            return mapper.readTree(response.contentAsString).also {
                assertEquals(code, it["status"].asInt())
                assertEquals(path, it["instance"].asText())
                assertValid("EventBatchProblem", it)
            }
        }
        val malformed = error(mapOf("events" to "invalid"), 400)
        assertEquals("INVALID_REQUEST", malformed["code"].asText())
        assertFalse(malformed.has("lastSequenceNumber"))
        assertFalse(malformed.has("logRevision"))
        val invalid = error(batch(emptyList()), 400)
        assertEquals(0, invalid["lastSequenceNumber"].asInt())
        val sequence = error(batch(listOf(pull(), pull())), 409)
        assertEquals("EVENT_SEQUENCE_VIOLATION", sequence["code"].asText())
        assertEquals(1, sequence["eventIndex"].asInt())
        assertTrue(sequence.has("eventId"))
        val stale = error(batch(listOf(pull()), sequence = 10), 409)
        assertEquals("EVENT_LOG_STALE", stale["code"].asText())
        assertFalse(stale.has("eventId"))
        assertFalse(stale.has("eventIndex"))
    }

    @Test
    fun `both instance schemas accept URI references and reject malformed references`() {
        val schemas = apiDocs()["components"]["schemas"]
        listOf("ProblemDetail", "EventBatchProblem").forEach { name ->
            val instance = schemas[name]["properties"]["instance"]
            assertEquals("uri-reference", instance["format"].asText(), name)
            val validator = factory.getSchema(instance, config)
            listOf("/api/v1/players", "relative/path?query=1#fragment", "https://example.org/errors/1")
                .forEach { assertTrue(validator.validate(mapper.valueToTree(it)).isEmpty(), "$name: $it") }
            listOf("bad reference", "https://[invalid", "/bad%ZZ")
                .forEach { assertTrue(validator.validate(mapper.valueToTree(it)).isNotEmpty(), "$name: $it") }
        }
    }

    @Test
    fun `missing match snapshot preserves empty 404 without content type and documents no content`() {
        val response = mvc.perform(get("/api/v2/matches/${UUID.randomUUID()}/events"))
            .andExpect(status().isNotFound).andReturn().response
        assertEquals(0, response.contentAsByteArray.size)
        assertNull(response.contentType)
        val documented = apiDocs()["paths"]["/api/v2/matches/{matchId}/events"]["get"]["responses"]["404"]
        assertTrue(documented.path("content").isMissingNode || documented.path("content").isEmpty,
            "Empty 404 must not advertise a response body: $documented")
        assertTrue(documented["description"].asText().contains("empty", ignoreCase = true))
    }

    private fun assertValid(name: String, body: JsonNode) {
        // Keep the raw component schemas and local references intact; no consumer normalization.
        val root = mapper.createObjectNode().apply {
            put("\$ref", "#/components/schemas/$name")
            set<ObjectNode>("components", apiDocs()["components"])
        }
        val errors = factory.getSchema(root, config).validate(body)
        assertTrue(errors.isEmpty(), "$name rejected actual HTTP body: $errors")
    }

    private fun apiDocs(): JsonNode = mapper.readTree(mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk).andReturn().response.contentAsString)

    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
}
