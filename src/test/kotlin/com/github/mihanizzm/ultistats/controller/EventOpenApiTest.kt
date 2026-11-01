package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateEventRequest
import com.github.mihanizzm.ultistats.model.events.EventType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(LegacyDomainHttpSecurity::class)
@SpringBootTest
@AutoConfigureMockMvc
class EventOpenApiTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    @ParameterizedTest
    @EnumSource(EventType::class)
    fun `each event type has a documented POST example accepted by the runtime DTO`(type: EventType) {
        val api = apiDocs()
        val examples = api["paths"][EVENTS_PATH]["post"]["requestBody"]["content"]["application/json"]["examples"]
        assertEquals(EventType.entries.map { it.name }.toSet(), examples.fieldNames().asSequence().toSet())
        val example = examples[type.name]
        assertTrue(example.path("summary").asText().isNotBlank())
        assertTrue(example.path("description").asText().isNotBlank())
        val payload = example["value"]
        val schemaName = CREATE_TYPES.entries.single { type.name in it.value }.key
        assertEquals(CREATE_FIELDS.getValue(schemaName) + "eventId", payload.fieldNames().asSequence().toSet())
        val request = objectMapper.treeToValue(payload, CreateEventRequest::class.java)
        assertEquals(type, request.type)
        assertEquals(schemaName, request.javaClass.simpleName)
    }

    @ParameterizedTest
    @ValueSource(strings = ["OnePlayerEventRequest", "TwoPlayerEventRequest", "TeamEventRequest", "SystemEventRequest"])
    fun `creation schemas describe all required fields and map types to their payload shape`(name: String) {
        val schemas = apiDocs()["components"]["schemas"]
        val schema = schemas[name]
        val properties = resolvedProperties(schema, schemas)
        assertEquals(CREATE_FIELDS.getValue(name), schema.path("required").map { it.asText() }.toSet())
        assertEquals(CREATE_FIELDS.getValue(name) + "eventId", properties.keys)
        CREATE_TYPES.getValue(name).forEach { type ->
            assertEquals("#/components/schemas/$name", schemas["CreateEventRequest"]["discriminator"]["mapping"][type].asText())
        }
        CREATE_FIELDS.getValue(name).forEach { field ->
            val property = properties.getValue(field)
            assertTrue(property.path("description").asText().isNotBlank(), "$name.$field needs a description")
            when {
                field.endsWith("Id") -> assertEquals("uuid", property["format"].asText())
                field == "occurredAt" -> assertEquals("date-time", property["format"].asText())
            }
        }
        assertEquals("uuid", properties.getValue("eventId")["format"].asText())
        assertTrue(properties.getValue("eventId").path("description").asText().isNotBlank())
    }

    @Test
    fun `POST documents idempotent creation responses`() {
        val operation = apiDocs()["paths"][EVENTS_PATH]["post"]
        assertTrue("eventId" in operation.path("description").asText())
        assertTrue(operation["responses"].has("200"))
        assertTrue(operation["responses"].has("201"))
        assertEquals(
            "#/components/schemas/ProblemDetail",
            operation["responses"]["409"]["content"]["application/problem+json"]["schema"]["\$ref"].asText(),
        )
    }

    @Test
    fun `PATCH schemas distinguish mandatory replacements from optional participant corrections`() {
        val schemas = apiDocs()["components"]["schemas"]
        val expectedFields = mapOf(
            "OnePlayerEventPatchRequest" to setOf("type", "participantId"),
            "TwoPlayerEventPatchRequest" to setOf("type", "fromParticipantId", "toParticipantId"),
            "TeamEventPatchRequest" to setOf("type", "teamId"),
        )
        val expectedRequired = expectedFields + ("TwoPlayerEventPatchRequest" to setOf("type"))
        expectedFields.forEach { (name, fields) ->
            val schema = schemas[name]
            val properties = resolvedProperties(schema, schemas)
            assertEquals(fields, properties.keys)
            assertEquals(expectedRequired.getValue(name), schema.path("required").map { it.asText() }.toSet())
            fields.forEach { field ->
                assertTrue(properties.getValue(field).path("description").asText().isNotBlank(), "$name.$field")
            }
        }
        val patch = schemas["UpdateEventRequest"]
        assertEquals("type", patch["discriminator"]["propertyName"].asText())
        assertEquals(
            expectedFields.keys.map { "#/components/schemas/$it" }.toSet(),
            patch["oneOf"].map { it["\$ref"].asText() }.toSet(),
        )
        val allowedTypes = CREATE_TYPES.filterKeys { it != "SystemEventRequest" }.values.flatten().toSet()
        assertEquals(allowedTypes, patch["discriminator"]["mapping"].fieldNames().asSequence().toSet())
    }

    @ParameterizedTest
    @ValueSource(strings = ["onePlayerEvent", "twoPlayerEvent", "blockRefinement", "teamEvent"])
    fun `PATCH examples deserialize without an occurrence time`(exampleName: String) {
        val operation = apiDocs()["paths"][EVENT_PATH]["patch"]
        val example = operation["requestBody"]["content"]["application/json"]["examples"][exampleName]
        assertTrue(example.path("description").asText().isNotBlank())
        val payload = example["value"]
        assertFalse(payload.has("occurredAt"))
        val request = objectMapper.treeToValue(payload, UpdateEventRequest::class.java)
        assertEquals(payload["type"].asText(), request.type.name)
        if (exampleName == "blockRefinement") {
            assertEquals(setOf("type"), payload.fieldNames().asSequence().toSet())
            assertEquals(EventType.BLOCK_MARKER, request.type)
        }
    }

    @Test
    fun `PATCH documents immutable time block refinement and unsupported system edits`() {
        val operation = apiDocs()["paths"][EVENT_PATH]["patch"]
        val description = operation.path("description").asText()
        listOf("occurredAt", "BLOCK", "BLOCK_MARKER", "BLOCK_FIELD", "HALFTIME_START", "HALFTIME_END")
            .forEach { assertTrue(it in description, "PATCH must explain $it") }
        assertTrue(operation["responses"].has("405"))
        assertEquals(
            "#/components/schemas/ProblemDetail",
            operation["responses"]["409"]["content"]["application/problem+json"]["schema"]["\$ref"].asText(),
        )
    }

    private fun apiDocs(): JsonNode = objectMapper.readTree(
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsString,
    )

    // Springdoc represents inherited request fields through allOf/$ref.
    private fun resolvedProperties(schema: JsonNode, schemas: JsonNode): Map<String, JsonNode> {
        val referenced = schema.path("\$ref").asText().takeIf { it.isNotEmpty() }
            ?.let { resolvedProperties(schemas[it.substringAfterLast('/')], schemas) }.orEmpty()
        val inherited = schema.path("allOf").flatMap { resolvedProperties(it, schemas).entries }
            .associate { it.toPair() }
        val own = schema.path("properties").properties().associate { it.toPair() }
        return referenced + inherited + own
    }

    private companion object {
        const val EVENTS_PATH = "/api/v1/matches/{matchId}/events"
        const val EVENT_PATH = "/api/v1/matches/{matchId}/events/{eventId}"
        val CREATE_TYPES = mapOf(
            "OnePlayerEventRequest" to setOf("INCOMPLETE_PASS", "PULL", "BRICK", "PICKUP"),
            "TwoPlayerEventRequest" to setOf("PASS", "GOAL", "BLOCK", "BLOCK_MARKER", "BLOCK_FIELD", "INTERCEPTION", "CALLAHAN"),
            "TeamEventRequest" to setOf("TIMEOUT_START", "TIMEOUT_END"),
            "SystemEventRequest" to setOf("HALFTIME_START", "HALFTIME_END"),
        )
        val CREATE_FIELDS = mapOf(
            "OnePlayerEventRequest" to setOf("type", "occurredAt", "participantId"),
            "TwoPlayerEventRequest" to setOf("type", "occurredAt", "fromParticipantId", "toParticipantId"),
            "TeamEventRequest" to setOf("type", "occurredAt", "teamId"),
            "SystemEventRequest" to setOf("type", "occurredAt"),
        )
    }
}
