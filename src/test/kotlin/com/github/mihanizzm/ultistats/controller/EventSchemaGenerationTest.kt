package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateEventRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class EventSchemaGenerationTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `raw event unions have independent discriminated leaves`() {
        val raw = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andReturn().response.contentAsString
        Files.createDirectories(Path.of("build/smoke128"))
        Files.writeString(Path.of("build/smoke128/openapi.json"), raw)
        val schemas = objectMapper.readTree(raw)["components"]["schemas"]
        val batchId = schemas["BatchEventRequest"]["properties"]["eventId"]
        assertTrue(batchId["description"].asText().contains("Required"), "batch eventId must describe its requirement")
        val fixtures = mutableListOf<Map<String, Any>>()
        listOf("CreateEventRequest", "UpdateEventRequest", "EventResponse").forEach { base ->
            val union = schemas[base]
            union["oneOf"].forEach { ref ->
                val name = ref["\$ref"].asText().substringAfterLast('/')
                val leaf = schemas[name]
                assertFalse(leaf.toString().contains("#/components/schemas/$base"), "$name inherits its union")
                val types = union["discriminator"]["mapping"].properties()
                    .filter { it.value.asText().endsWith("/$name") }.map { it.key }.toSet()

                assertEquals("string", leaf["properties"]["type"].path("type").asText(), "$name discriminator must be a string")
                assertEquals(types, leaf["properties"]["type"]["enum"].map { it.asText() }.toSet(), name)
                val example = leaf["properties"]["type"]["example"]
                if (example != null) assertTrue(example.asText() in types, "$name discriminator example")
                types.forEach { eventType ->
                    val payload = objectMapper.createObjectNode()
                    leaf["properties"].properties().forEach { (field, _) ->
                        when {
                            field == "type" -> payload.put(field, eventType)
                            field == "occurredAt" -> payload.put(field, "2026-07-28T12:30:00Z")
                            field == "sequenceNumber" -> payload.put(field, 1)
                            field.endsWith("Id") || field == "id" ->
                                payload.put(field, "11111111-1111-1111-1111-111111111111")
                        }
                    }
                    val dto = when (base) {
                        "CreateEventRequest" -> objectMapper.treeToValue(payload, CreateEventRequest::class.java)
                        "UpdateEventRequest" -> objectMapper.treeToValue(payload, UpdateEventRequest::class.java)
                        else -> objectMapper.treeToValue(payload, Class.forName(
                            "com.github.mihanizzm.ultistats.dto.response.$name",
                        ))
                    }
                    if (name == "TwoPlayerEventPatchRequest") {
                        listOf(
                            """{"type":"$eventType"}""",
                            """{"type":"$eventType","fromParticipantId":null,"toParticipantId":null}""",
                        ).forEach { json ->
                            val patch = objectMapper.readValue(json, UpdateEventRequest::class.java)
                            assertEquals(eventType, patch.type.name)
                            fixtures += mapOf("schema" to base, "payload" to objectMapper.readTree(json))
                        }
                    }
                    fixtures += mapOf("schema" to base, "payload" to objectMapper.valueToTree<JsonNode>(dto))
                }
            }
        }
        objectMapper.writeValue(Path.of("build/smoke128/runtime-fixtures.json").toFile(), fixtures)
    }
}
