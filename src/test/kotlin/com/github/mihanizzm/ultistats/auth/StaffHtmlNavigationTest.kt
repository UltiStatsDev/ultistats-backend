package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.*

class StaffHtmlNavigationTest {
    private val mapper = jacksonObjectMapper()
    private fun writer(mode: AuthEntryMode = AuthEntryMode.SECURE, secureOrigin: String = "https://staff.example") =
        AuthProblemWriter(mapper, StaffHtmlEntryNavigation(object : AuthOriginConfiguration {
            override fun secureOrigins() = setOf(secureOrigin)
            override fun secureOrigin() = secureOrigin
            override fun publicOrigin() = "http://public.example"
        }, object : AuthEntryClassifier {
            override fun classify(request: jakarta.servlet.http.HttpServletRequest) = mode
        }))
    private fun request(path: String, method: String = "GET", accept: String = "text/html") =
        MockHttpServletRequest(method, path).apply { addHeader("Accept", accept) }

    @Test fun `native staff document auth denial navigates to fixed secure login and canonical return`() {
        for ((path, canonical) in mapOf("/obs-admin.html" to "%2Fobs-admin.html", "/swagger-ui.html" to "%2Fswagger-ui.html", "/swagger-ui/index.html" to "%2Fswagger-ui.html")) {
            for (code in listOf("AUTH_REQUIRED", "AUTH_SESSION_INVALID", "AUTH_SESSION_EXPIRED", "AUTH_SESSION_REVOKED")) {
                val response = MockHttpServletResponse()
                writer().write(request(path).apply {
                    queryString = "returnTo=https://evil.example&token=untrusted"
                    addHeader("Host", "evil.example"); addHeader("X-Forwarded-Host", "evil.example")
                    addHeader("Sec-Fetch-Mode", "navigate")
                }, response, AuthProblem(401, code))
                assertEquals(302, response.status)
                assertEquals("https://staff.example/login?returnTo=$canonical", response.getHeader("Location"))
                assertEquals("no-store", response.getHeader("Cache-Control"))
                assertTrue(response.getHeaders("Set-Cookie").isEmpty())
                assertEquals("", response.contentAsString)
            }
        }
    }
    @Test fun `temporary document session goes to own password route with return preserved`() {
        val response = MockHttpServletResponse()
        writer().write(request("/obs-admin.html"), response, AuthProblem(403, "AUTH_PASSWORD_CHANGE_REQUIRED"))
        assertEquals(302, response.status)
        assertEquals("https://staff.example/account/password?returnTo=%2Fobs-admin.html", response.getHeader("Location"))
        assertTrue(response.getHeaders("Set-Cookie").isEmpty())
    }
    @Test fun `explicit loopback may navigate to configured loopback gateway`() {
        val response = MockHttpServletResponse()
        writer(AuthEntryMode.LOOPBACK, "http://127.0.0.1:5173").write(request("/swagger-ui.html"), response, AuthProblem(401, "AUTH_REQUIRED"))
        assertEquals("http://127.0.0.1:5173/login?returnTo=%2Fswagger-ui.html", response.getHeader("Location"))
    }
    @Test fun `public transport malformed targets APIs assets XHR and captured intents retain JSON problems`() {
        val cases = listOf(request("/api/v1/teams"), request("/v3/api-docs"), request("/auth/swagger.mjs"),
            request("/unknown"), request("/swagger-ui%2Ehtml"), request("/obs-admin.html", "POST"),
            request("/obs-admin.html", accept = "application/json"), request("/obs-admin.html", accept = "text/html;q=0"),
            request("/obs-admin.html").apply { addHeader("X-Requested-With", "XMLHttpRequest") },
            request("/obs-admin.html").apply { addHeader("Sec-Fetch-Mode", "cors") },
            request("/obs-admin.html").apply { addHeader("X-UltiStats-Installation-Id", "captured") })
        for (input in cases) {
            val response = MockHttpServletResponse()
            writer().write(input, response, AuthProblem(401, "AUTH_REQUIRED"))
            assertEquals(401, response.status, input.requestURI)
            assertNull(response.getHeader("Location"))
            assertEquals("AUTH_REQUIRED", mapper.readTree(response.contentAsString)["code"].asText())
        }
        for ((mode, target) in listOf(AuthEntryMode.ANONYMOUS to "https://staff.example", AuthEntryMode.SECURE to "http://staff.example", AuthEntryMode.LOOPBACK to "http://lan.example")) {
            val response = MockHttpServletResponse()
            writer(mode, target).write(request("/obs-admin.html"), response, AuthProblem(401, "AUTH_REQUIRED"))
            assertEquals(401, response.status);assertNull(response.getHeader("Location"))
        }
        for (code in listOf("AUTH_IDENTITY_MISMATCH", "AUTH_CONTEXT_REQUIRED", "PUBLIC_TRANSPORT_READ_ONLY", "AUTH_PERMISSION_DENIED")) {
            val response = MockHttpServletResponse()
            writer().write(request("/obs-admin.html"), response, AuthProblem(403, code))
            assertEquals(403, response.status);assertNull(response.getHeader("Location"))
        }
    }
    @Test fun `actual core chain routes initial stale and temporary documents but keeps APIs and public entry JSON`() {
        org.springframework.web.context.support.AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = org.springframework.mock.web.MockServletContext()
            context.register(AuthSurfaceMatrixTest.Config::class.java, AuthSecurityConfig::class.java,
                AuthSurfacePolicyImpl::class.java, StaffHtmlEntryNavigation::class.java, AuthSurfaceMatrixTest.Probe::class.java)
            context.refresh()
            val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build()
            val fixture = context.getBean(AuthTestFixture::class.java)
            val temporary = fixture.login()
            for (path in listOf("/obs-admin.html", "/swagger-ui.html", "/swagger-ui/index.html")) {
                val canonical = if (path.startsWith("/swagger-ui")) "%2Fswagger-ui.html" else "%2Fobs-admin.html"
                val initial = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).accept("text/html")).andReturn().response
                assertEquals(302, initial.status)
                assertEquals("http://localhost:21153/login?returnTo=$canonical", initial.getHeader("Location"))
                val stale = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).accept("text/html")
                    .cookie(jakarta.servlet.http.Cookie("ULTISTATS_LOOPBACK_SESSION", "forged"))).andReturn().response
                assertEquals(302, stale.status)
                assertEquals("http://localhost:21153/login?returnTo=$canonical", stale.getHeader("Location"))
                val temp = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).accept("text/html")
                    .cookie(jakarta.servlet.http.Cookie("ULTISTATS_LOOPBACK_SESSION", temporary.credential))).andReturn().response
                assertEquals(302, temp.status)
                assertEquals("http://localhost:21153/account/password?returnTo=$canonical", temp.getHeader("Location"))
                assertTrue(initial.getHeaders("Set-Cookie").isEmpty())
                assertTrue(stale.getHeaders("Set-Cookie").isEmpty())
                assertTrue(temp.getHeaders("Set-Cookie").isEmpty())
            }
            for (path in listOf("/v3/api-docs", "/auth/staff-session.mjs")) {
                val response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).accept("text/html")).andReturn().response
                assertEquals(401, response.status);assertNull(response.getHeader("Location"))
                assertEquals("AUTH_REQUIRED", mapper.readTree(response.contentAsString)["code"].asText())
            }
            context.getBean(AuthSurfaceMatrixTest.Mode::class.java).value = AuthEntryMode.ANONYMOUS
            val public = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/obs-admin.html").accept("text/html")).andReturn().response
            assertEquals(403, public.status);assertNull(public.getHeader("Location"))
            assertEquals("PUBLIC_TRANSPORT_READ_ONLY", mapper.readTree(public.contentAsString)["code"].asText())
        }
    }

}
