package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.controller.AuthController
import com.github.mihanizzm.ultistats.auth.controller.AccountController
import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.*
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockServletContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Clock
import kotlin.test.*

class AuthHttpIntegrationTest {
    @Configuration @EnableWebMvc
    class Config {
        @Bean fun fixture()=AuthTestFixture()
        @Bean fun authService(fixture:AuthTestFixture)=fixture.service
        @Bean fun store(fixture:AuthTestFixture)=fixture.store
        @Bean fun clock(fixture:AuthTestFixture):Clock=fixture.clock
        @Bean fun mapper():ObjectMapper=jacksonObjectMapper().findAndRegisterModules()
        @Bean fun mode()=Mode()
        @Bean fun classifier(mode:Mode)=object:AuthEntryClassifier {
            override fun classify(request:jakarta.servlet.http.HttpServletRequest)=mode.value
        }
        @Bean fun origins()=object:AuthOriginConfiguration {
            override fun secureOrigins()=setOf("http://localhost:21152")
            override fun publicOrigin()="http://localhost:21152"
            override fun secureOrigin()="http://localhost:21152"
        }
    }
    class Mode(var value:AuthEntryMode=AuthEntryMode.LOOPBACK)
    private lateinit var context:AnnotationConfigWebApplicationContext
    private lateinit var mvc:MockMvc
    private lateinit var fixture:AuthTestFixture
    private lateinit var mapper:ObjectMapper
    private var csrfCookie:Cookie?=null
    private var csrfToken=""
    @BeforeEach fun setup() {
        context=AnnotationConfigWebApplicationContext().apply {
            servletContext=MockServletContext()
            register(Config::class.java,AuthSecurityConfig::class.java,AuthController::class.java,AccountController::class.java)
            refresh()
        }
        fixture=context.getBean(AuthTestFixture::class.java)
        mapper=context.getBean(ObjectMapper::class.java)
        mvc=MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
        csrf()
    }
    @AfterEach fun close() { context.close() }
    private fun csrf(session:Cookie?=null) {
        val builder=get("/api/v1/auth/csrf")
        listOfNotNull(csrfCookie,session).takeIf { it.isNotEmpty() }?.let { builder.cookie(*it.toTypedArray()) }
        val result=mvc.perform(builder).andReturn()
        assertEquals(200,result.response.status)
        csrfToken=mapper.readTree(result.response.contentAsString)["token"].asText()
        csrfCookie=result.response.getCookie("ULTISTATS_LOOPBACK_CSRF") ?: csrfCookie
        assertNotNull(csrfCookie)
    }
    private fun login():MvcResult=mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie!!)
        .header("X-CSRF-TOKEN",csrfToken).header("X-UltiStats-Installation-Id",fixture.service.installationId())
        .contentType("application/json").content("""{"login":"owner","password":"owner-password"}""")).andReturn()

    @Test fun `masked JSON CSRF token permits login and refresh is required after login`() {
        val login=login()
        assertEquals(200,login.response.status,login.response.contentAsString)
        val cookie=login.response.getCookie("ULTISTATS_LOOPBACK_SESSION")!!
        assertTrue(cookie.isHttpOnly)
        assertEquals("/",cookie.path)
        assertEquals(14*86400,cookie.maxAge)
        assertNull(cookie.domain)
        val body=mapper.readTree(login.response.contentAsString)
        assertEquals("PASSWORD_CHANGE_REQUIRED",body["state"].asText())
        assertTrue(body["permissions"].isEmpty)
        assertFalse(body.has("credential"))
        assertEquals("no-store",login.response.getHeader("Cache-Control"))
        // Login clears the CSRF cookie. A new GET provisions a fresh masked token.
        csrfCookie=null
        val oldToken=csrfToken
        csrf(cookie)
        assertNotEquals(oldToken,csrfToken)
        val changed=mvc.perform(post("/api/v1/auth/password").cookie(cookie,csrfCookie!!)
            .header("X-CSRF-TOKEN",csrfToken).header("X-UltiStats-Installation-Id",body["installationId"].asText())
            .header("X-UltiStats-Account-Id",body["account"]["id"].asText()).header("X-UltiStats-Session-Id",body["sessionId"].asText())
            .contentType("application/json").content("""{"currentPassword":"owner-password","newPassword":"permanent-password"}""")).andReturn()
        assertEquals(200,changed.response.status,changed.response.contentAsString)
        assertEquals("AUTHENTICATED",mapper.readTree(changed.response.contentAsString)["state"].asText())
        assertEquals(0,changed.response.getCookie("ULTISTATS_LOOPBACK_CSRF")!!.maxAge)
    }

    @Test fun `private capture mismatch is rejected before context or CSRF cookie refresh`() {
        val login=login()
        val cookie=login.response.getCookie("ULTISTATS_LOOPBACK_SESSION")!!
        val result=mvc.perform(get("/api/v1/auth/session").cookie(cookie)
            .header("X-UltiStats-Installation-Id",fixture.service.installationId())).andReturn()
        assertEquals(403,result.response.status)
        assertEquals("AUTH_CONTEXT_REQUIRED",mapper.readTree(result.response.contentAsString)["code"].asText())
        assertTrue(mapper.readTree(result.response.contentAsString)["requestRejectedBeforeExecution"].asBoolean())
        assertTrue(result.response.getHeaders("Set-Cookie").isEmpty())
        assertFalse(result.response.contentAsString.contains("owner"))
    }

    @Test fun `anonymous listener rejects forged credentials headers and malformed body before authentication`() {
        context.getBean(Mode::class.java).value=AuthEntryMode.ANONYMOUS
        val response=mvc.perform(post("/api/v1/auth/login").header("X-Forwarded-Proto","https")
            .header("Host","secure.example").header("Authorization","Bearer forged")
            .cookie(Cookie("__Host-ULTISTATS_SESSION","forged")).contentType("application/json").content("{" )).andReturn().response
        assertEquals(403,response.status)
        assertEquals("PUBLIC_TRANSPORT_READ_ONLY",mapper.readTree(response.contentAsString)["code"].asText())
        assertTrue(response.getHeaders("Set-Cookie").isEmpty())
    }

    @Test fun `unsafe login requires CSRF and rejects unapproved origin without HTML redirect`() {
        val noCsrf=mvc.perform(post("/api/v1/auth/login").header("X-UltiStats-Installation-Id",fixture.service.installationId())
            .contentType("application/json").content("""{"login":"owner","password":"owner-password"}""")).andReturn().response
        assertEquals(403,noCsrf.status)
        assertEquals("CSRF_INVALID",mapper.readTree(noCsrf.contentAsString)["code"].asText())
        assertNull(noCsrf.getHeader("Location"))
        val foreign=mvc.perform(post("/api/v1/auth/login").cookie(csrfCookie!!).header("Origin","http://evil.example")
            .header("X-CSRF-TOKEN",csrfToken).header("X-UltiStats-Installation-Id",fixture.service.installationId())
            .contentType("application/json").content("{}" )).andReturn().response
        assertEquals(403,foreign.status)
        assertEquals("CSRF_INVALID",mapper.readTree(foreign.contentAsString)["code"].asText())
    }
}
