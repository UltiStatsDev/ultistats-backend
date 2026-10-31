package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@Import(ObsDatabaseHelper::class)
class ObsConfigIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var database: ObsDatabaseHelper
    @Autowired lateinit var service: ObsConfigService
    @Autowired lateinit var transaction: TransactionTemplate

    @Autowired lateinit var realtime: com.github.mihanizzm.ultistats.realtime.MatchRealtimePublisher

    @BeforeEach fun reset() { database.resetConfig() }

    private fun defaults() = mvc.perform(get("/api/v1/obs/config"))
        .andExpect(status().isOk).andReturn().response.contentAsString

    @Test fun `defaults are shared and saved config survives independent database reads`() {
        val config = json.readTree(defaults())
        org.junit.jupiter.api.Assertions.assertEquals(5, config["maxRows"].asInt())
        org.junit.jupiter.api.Assertions.assertEquals(8, config["ttlSeconds"].asInt())
        val updated = config.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            .put("maxRows", 12).put("corner", "BOTTOM_LEFT")
        mvc.perform(put("/api/v1/obs/config").contentType(MediaType.APPLICATION_JSON).content(updated.toString()))
            .andExpect(status().isOk).andExpect(jsonPath("$.maxRows").value(12))
        val persisted = database.persistedConfig()
        org.junit.jupiter.api.Assertions.assertEquals(12, json.readTree(persisted)["maxRows"].asInt())
        mvc.perform(get("/api/v1/obs/config")).andExpect(jsonPath("$.corner").value("BOTTOM_LEFT"))
    }

    @Test fun `reject invalid config without persisting it`() {
        val valid = json.readTree(defaults())
        listOf("maxRows" to "0", "maxRows" to "21", "ttlSeconds" to "61",
            "backgroundColor" to "\"red\"", "corner" to "\"CENTER\"",
            "categories" to "[null]",
            "teamColors" to "{\"00000000-0000-0000-0000-000000000001\":null}",
            "teamColors" to "{\"not-a-uuid\":\"#ffffff\"}",
            "teamColors" to "{\"00000000-0000-0000-0000-000000000001\":\"url(x)\"}").forEach { (key, value) ->
            val invalid = valid.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            invalid.set<com.fasterxml.jackson.databind.JsonNode>(key, json.readTree(value))
            mvc.perform(put("/api/v1/obs/config").contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.detail").exists())
        }
        org.junit.jupiter.api.Assertions.assertEquals("{}", database.persistedConfig())
    }

    @Test fun `stream greets then notifies saved config without creating gaming events`() {
        val count = database.gameState()
        val stream = mvc.perform(get("/api/v1/obs/config/stream")).andExpect(request().asyncStarted()).andReturn()
        mvc.perform(put("/api/v1/obs/config").contentType(MediaType.APPLICATION_JSON).content(defaults()))
            .andExpect(status().isOk)
        org.junit.jupiter.api.Assertions.assertTrue(stream.response.contentAsString.contains("event:connected"))
        org.junit.jupiter.api.Assertions.assertTrue(stream.response.contentAsString.contains("event:config-changed"))
        org.junit.jupiter.api.Assertions.assertEquals(count, database.gameState())
    }

    @Test fun `new APIs appear in OpenAPI`() {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(content().string(containsString("/api/v1/obs/config/stream")))
    }
    @Test fun `rolled back config does not leak notification or changed state`() {
        val stream = mvc.perform(get("/api/v1/obs/config/stream")).andReturn()
        transaction.executeWithoutResult { status ->
            service.save(ObsConfig(maxRows = 19))
            status.setRollbackOnly()
        }
        org.junit.jupiter.api.Assertions.assertEquals(5, service.get().maxRows)
        org.junit.jupiter.api.Assertions.assertFalse(stream.response.contentAsString.contains("config-changed"))
    }
    @Test fun `shared OBS transport documents raw schema and delivers only committed notifications`() {
        val stream = mvc.perform(get("/api/v1/obs/stream")).andExpect(status().isOk)
            .andExpect(request().asyncStarted()).andReturn()
        try {
            val matchId = java.util.UUID.randomUUID()
            val eventId = java.util.UUID.randomUUID()
            transaction.executeWithoutResult {
                service.save(ObsConfig(maxRows = 9))
                realtime.eventLogChanged(matchId, com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation.UPDATED, eventId)
                realtime.matchFinished(matchId)
                org.assertj.core.api.Assertions.assertThat(stream.response.contentAsString)
                    .doesNotContain("event:config-changed", "event:event-log-changed", "event:match-finished")
            }
            org.assertj.core.api.Assertions.assertThat(stream.response.contentAsString)
                .contains("event:connected", "event:config-changed", "event:event-log-changed", "event:match-finished", matchId.toString(), eventId.toString())
            // Shared config remains live after a finished match; this is not a terminal match subscription.
            val before = stream.response.contentAsString
            service.save(ObsConfig(maxRows = 10))
            org.assertj.core.api.Assertions.assertThat(stream.response.contentAsString.length).isGreaterThan(before.length)
            val committed = stream.response.contentAsString
            transaction.executeWithoutResult { status ->
                service.save(ObsConfig(maxRows = 11))
                realtime.eventLogChanged(matchId, com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation.DELETED, eventId)
                realtime.matchFinished(matchId)
                status.setRollbackOnly()
            }
            org.assertj.core.api.Assertions.assertThat(stream.response.contentAsString).isEqualTo(committed)
            org.assertj.core.api.Assertions.assertThat(service.get().maxRows).isEqualTo(10)
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
                .andExpect(jsonPath("$.paths['/api/v1/obs/stream'].get.responses['200'].content['text/event-stream'].schema.type").value("string"))
        } finally { runCatching { stream.request.asyncContext?.complete() } }
    }

}
