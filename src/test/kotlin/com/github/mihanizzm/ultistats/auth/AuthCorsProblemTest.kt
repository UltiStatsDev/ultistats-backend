package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.controller.AuthController
import com.github.mihanizzm.ultistats.auth.security.AuthSecurityConfig
import com.github.mihanizzm.ultistats.config.StorageProperties
import com.github.mihanizzm.ultistats.config.WebConfig
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockServletContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import kotlin.test.assertEquals

class AuthCorsProblemTest {
    @Configuration class Storage { @Bean fun storage() = StorageProperties() }
    @Test fun `CORS rejects wrong-origin public reads and preflights as raw ProblemDetail without cookies`() {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.register(AuthSurfaceMatrixTest.Config::class.java, Storage::class.java, WebConfig::class.java,
                AuthSecurityConfig::class.java, AuthController::class.java,
                com.github.mihanizzm.ultistats.auth.security.AuthSurfacePolicyImpl::class.java, AuthSurfaceMatrixTest.Probe::class.java)
            context.refresh()
            val mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            val calls = listOf(get("/api/v1/teams").header("Origin", "https://attacker.example.test"),
                options("/api/v1/matches").header("Origin", "https://attacker.example.test")
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-CSRF-TOKEN"))
            for (call in calls) {
                val response = mvc.perform(call).andReturn().response
                assertEquals(403, response.status)
                assertEquals(null, response.getHeader("Access-Control-Allow-Origin"))
                assertEquals("application/problem+json", response.contentType?.substringBefore(';'))
                val problem = context.getBean(com.fasterxml.jackson.databind.ObjectMapper::class.java).readTree(response.contentAsString)
                assertEquals("CSRF_INVALID", problem["code"].asText())
                assertEquals(true, problem["requestRejectedBeforeExecution"].asBoolean())
                assertEquals(emptyList(), response.getHeaders("Set-Cookie").toList())
            }

            val permitted = mvc.perform(options("/api/v1/matches").header("Origin", "http://localhost:21153")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-CSRF-TOKEN,X-UltiStats-Session-Id")).andReturn().response
            assertEquals(200, permitted.status)
            assertEquals("http://localhost:21153", permitted.getHeader("Access-Control-Allow-Origin"))
            assertEquals("true", permitted.getHeader("Access-Control-Allow-Credentials"))
            context.getBean(AuthSurfaceMatrixTest.Mode::class.java).value = com.github.mihanizzm.ultistats.auth.model.AuthEntryMode.ANONYMOUS
            val anonymous = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/matches")
                .header("Origin", "https://attacker.example.test").header("Authorization", "Bearer forged")
                .contentType("application/json").content("{malformed")).andReturn().response
            assertEquals(403, anonymous.status)
            val problem = context.getBean(com.fasterxml.jackson.databind.ObjectMapper::class.java).readTree(anonymous.contentAsString)
            assertEquals("PUBLIC_TRANSPORT_READ_ONLY", problem["code"].asText())
            assertEquals(emptyList(), anonymous.getHeaders("Set-Cookie").toList())
        }
    }
}
