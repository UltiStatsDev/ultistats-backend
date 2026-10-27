package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Suppress("NonAsciiCharacters")
class PatchOpenApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `OpenAPI публикует PATCH и помечает старые PUT как deprecated`() {
        val document = openApiDocument()

        mapOf(
            "/api/v1/players/{id}" to "PatchPlayerRequest",
            "/api/v1/teams/{id}" to "PatchTeamRequest",
            "/api/v1/matches/{id}" to "PatchMatchRequest",
        ).forEach { (path, requestSchema) ->
            val operation = document.path("paths").path(path)
            assertEquals(true, operation.path("put").path("deprecated").asBoolean())
            assertEquals(
                "#/components/schemas/$requestSchema",
                operation.path("patch").path("requestBody").path("content")
                    .path("application/json").path("schema").path("\$ref").asText(),
            )
        }
    }

    @Test
    fun `OpenAPI объясняет семантику пропущенных полей и null на примерах`() {
        val schemas = openApiDocument().path("components").path("schemas")

        listOf("PatchPlayerRequest", "PatchTeamRequest", "PatchMatchRequest").forEach { schemaName ->
            val schema = schemas.path(schemaName)
            assertTrue(schema.path("description").asText().contains("Пропущенные поля"))
            assertNotNull(schema.get("example"))
        }

        assertTrue(
            schemas.path("PatchTeamRequest").path("properties").path("city")
                .path("description").asText().contains("null очищает"),
        )
        val citySchema = schemas.path("PatchTeamRequest").path("properties").path("city")
        assertTrue(citySchema.path("type").any { it.asText() == "null" }, citySchema.toPrettyString())
        assertTrue(!citySchema.has("\$ref"), citySchema.toPrettyString())
        assertTrue(
            schemas.path("PatchMatchRequest").path("properties").path("plannedStartTimestamp")
                .path("description").asText().contains("null очищает"),
        )
        val timestampSchema = schemas.path("PatchMatchRequest").path("properties").path("plannedStartTimestamp")
        assertTrue(timestampSchema.path("type").any { it.asText() == "null" }, timestampSchema.toPrettyString())
        assertEquals("date-time", timestampSchema.path("format").asText())
        assertTrue(!timestampSchema.has("\$ref"), timestampSchema.toPrettyString())
        assertTrue(
            schemas.path("PatchMatchRequest").path("properties").path("playersPerTeam")
                .path("description").asText().contains("Минимум 2"),
        )
        val teamIdsSchema = schemas.path("PatchMatchRequest").path("properties").path("teamIds")
        assertEquals("array", teamIdsSchema.path("type").asText(), teamIdsSchema.toPrettyString())
        assertTrue(!teamIdsSchema.has("\$ref"), teamIdsSchema.toPrettyString())
    }

    private fun openApiDocument(): JsonNode {
        val response = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString
        return objectMapper.readTree(response)
    }
}
