package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.config.StorageProperties
import com.github.mihanizzm.ultistats.config.WebConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.servlet.config.annotation.CorsRegistry

class AuthCorsTest {
    private class Registry : CorsRegistry() { fun configuration() = corsConfigurations.getValue("/api/**") }
    private val origins = object : AuthOriginConfiguration {
        override fun secureOrigins() = setOf("https://staff.example", "http://127.0.0.1:21153")
        override fun publicOrigin() = "http://public.example"
        override fun secureOrigin() = "https://staff.example"
    }
    private fun preflight(origin: String): Pair<Boolean, MockHttpServletResponse> {
        val registry = Registry()
        WebConfig(StorageProperties(), origins).addCorsMappings(registry)
        val request = MockHttpServletRequest("OPTIONS", "/api/v1/matches").apply {
            addHeader("Origin", origin)
            addHeader("Access-Control-Request-Method", "POST")
            addHeader("Access-Control-Request-Headers", "X-CSRF-TOKEN,X-UltiStats-Session-Id,Content-Type")
        }
        val response = MockHttpServletResponse()
        return DefaultCorsProcessor().processRequest(registry.configuration(), request, response) to response
    }
    @Test fun `configured private origins can preflight credentialed protected requests`() {
        for (origin in origins.secureOrigins()) {
            val (allowed, response) = preflight(origin)
            assertTrue(allowed)
            assertEquals(origin, response.getHeader("Access-Control-Allow-Origin"))
            assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        }
    }
    @Test fun `unconfigured and public origins cannot preflight credentialed staff requests`() {
        for (origin in listOf("https://evil.example", "https://staff.example.evil.example", "null", "http://public.example")) {
            val (allowed, response) = preflight(origin)
            assertFalse(allowed)
            assertEquals(403, response.status)
            assertEquals(null, response.getHeader("Access-Control-Allow-Credentials"))
        }
    }
}
