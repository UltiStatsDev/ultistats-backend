package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(LegacyDomainHttpSecurity::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("NonAsciiCharacters")
class ObsDiagnosticPageTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `диагностическая OBS страница доступна как HTML`() {
        mockMvc.perform(get("/obs-diagnostic.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("UltiStats OBS diagnostics")))
    }
    @Test
    fun `broadcast and admin pages share renderer assets`() {
        listOf("/obs-battle-log.html", "/obs-admin.html", "/obs/renderer.mjs", "/obs/overlay.mjs").forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isOk)
        }
    }
}
