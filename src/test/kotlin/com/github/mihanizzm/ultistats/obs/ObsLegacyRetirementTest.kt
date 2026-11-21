package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.http.HttpMethod

@SpringBootTest
@AutoConfigureMockMvc
@Import(LegacyDomainHttpSecurity::class)
class ObsLegacyRetirementTest {
    @Autowired lateinit var mvc: MockMvc

    // This domain-only fixture bypasses auth; real boundary is tested in policy and HTTPS matrix.
    @Test fun `retired streams return gone for GET and HEAD even with SSE accept without async subscription`() {
        for (path in listOf("/api/v1/obs/stream", "/api/v1/obs/config/stream")) {
            for (method in listOf(HttpMethod.GET, HttpMethod.HEAD)) {
                val result = mvc.perform(request(method, path).accept("text/event-stream")).andReturn()
                try {
                    assertEquals(410, result.response.status, "$method $path")
                    assertFalse(result.request.isAsyncStarted, path)
                    assertEquals("no-store", result.response.getHeader("Cache-Control"))
                    assertTrue(result.response.contentType!!.startsWith("text/plain"))
                    assertFalse(result.response.contentAsString.contains("event:"))
                    // MockMvc lacks container HEAD body suppression; the real HTTPS matrix checks empty HEAD bodies.
                } finally { runCatching { result.request.asyncContext?.complete() } }
            }
        }
    }
}
