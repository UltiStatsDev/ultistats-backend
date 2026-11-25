package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
@Import(LegacyDomainHttpSecurity::class)
class ObsInfographicsSettingsTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var service: ObsInfographicsSettingsService
    @Autowired lateinit var json: com.fasterxml.jackson.databind.ObjectMapper
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    private val path = "/api/v1/obs/infographics/settings"
    private fun write(body: String) = mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(body))

    @Test fun `three independent settings persist and old config cannot erase them`() {
        write("""{"rosterPageSeconds":2,"pointPageSeconds":15,"leaderPageSeconds":7}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.revision").isNumber)
        mvc.perform(get(path)).andExpect(status().isOk)
            .andExpect(jsonPath("$.rosterPageSeconds").value(2))
            .andExpect(jsonPath("$.pointPageSeconds").value(15))
            .andExpect(jsonPath("$.leaderPageSeconds").value(7))
        mvc.perform(put("/api/v1/obs/config").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk)
        mvc.perform(get(path)).andExpect(jsonPath("$.pointPageSeconds").value(15))
    }

    @Test fun `same values keep revision and history while new values affect only later epochs`() {
        val before = java.time.Instant.now().minusSeconds(60)
        val saved = service.save(json.readTree("""{"rosterPageSeconds":3,"pointPageSeconds":8,"leaderPageSeconds":11}"""))
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM obs_infographics_settings_history", Long::class.java)
        kotlin.test.assertEquals(saved, service.save(json.valueToTree(saved)))
        kotlin.test.assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM obs_infographics_settings_history", Long::class.java))
        kotlin.test.assertEquals(ObsInfographicsSettings(), service.at(before))
        kotlin.test.assertEquals(saved, service.at(java.time.Instant.now().plusSeconds(1)))
        kotlin.test.assertEquals(5, service.at(null).rosterPageSeconds)
    }

    @Test fun `strict local JSON integers reject coercion missing and null`() {
        listOf("1", "16", "3.5", "5.0", "\"5\"", "true", "null", "2147483648").forEach { value ->
            write("""{"rosterPageSeconds":$value,"pointPageSeconds":5,"leaderPageSeconds":5}""")
                .andExpect(status().isBadRequest)
        }
        write("""{"pointPageSeconds":5,"leaderPageSeconds":5}""").andExpect(status().isBadRequest)
    }
}
