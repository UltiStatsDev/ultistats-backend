package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.controller.AuthController
import com.github.mihanizzm.ultistats.auth.controller.AccountController
import com.github.mihanizzm.ultistats.auth.controller.AuthExceptionHandler
import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.security.*
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.service.MatchService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.mock.web.MockServletContext
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** Real core chain + persistent H2 auth authority + production policy, with a domain-entry probe only. */
class AuthSurfaceMatrixTest {
    class Mode(var value: AuthEntryMode = AuthEntryMode.LOOPBACK)
    @RestController class Probe {
        val calls = AtomicInteger()
        @RequestMapping("/**") fun reached() = mapOf("reached" to calls.incrementAndGet())
    }
    @TestConfiguration @EnableWebMvc class Config {
        @Bean fun fixture() = AuthTestFixture()
        @Bean fun service(f: AuthTestFixture) = f.service
        @Bean fun store(f: AuthTestFixture) = f.store
        @Bean fun fence(f: AuthTestFixture) = com.github.mihanizzm.ultistats.auth.repository.AuthTransactionFence(f.jdbc)
        @Bean fun clock(f: AuthTestFixture): java.time.Clock = f.clock
        @Bean fun mapper(): ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        @Bean fun mode() = Mode()
        @Bean fun classifier(mode: Mode) = object : AuthEntryClassifier {
            override fun classify(request: jakarta.servlet.http.HttpServletRequest) = mode.value
        }
        @Bean fun origins() = object : AuthOriginConfiguration {
            override fun secureOrigins() = setOf("http://localhost:21153")
            override fun publicOrigin() = "http://localhost:21153"
            override fun secureOrigin() = "http://localhost:21153"
        }
        @Bean fun matches() = Mockito.mock(MatchService::class.java)
    }
    @Test
    fun `reviewed matrix gates guest organizer admin temp and anonymous transport before domain entry`() {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.register(Config::class.java, AuthSecurityConfig::class.java, AuthSurfacePolicyImpl::class.java, AuthController::class.java, Probe::class.java)
            context.refresh()
            val fixture = context.getBean(AuthTestFixture::class.java)
            val admin = fixture.activeLogin()
            val created = fixture.service.createAccount(admin.context.principal(), CreateAccountRequest("organizer", "Organizer", AuthRole.ORGANIZER))
            val first = fixture.service.login(created.account.login, created.temporaryPassword, null)
            fixture.service.changePassword(first.context.principal(), created.temporaryPassword, "organizer-password")
            val organizer = fixture.service.login("organizer", "organizer-password", null)
            val tempAccount = fixture.service.createAccount(admin.context.principal(), CreateAccountRequest("temporary", "Temporary", AuthRole.ORGANIZER))
            val temp = fixture.service.login(tempAccount.account.login, tempAccount.temporaryPassword, null)
            val mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            val mapper = context.getBean(ObjectMapper::class.java)
            val csrfResult = mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf")).andReturn()
            assertEquals(200, csrfResult.response.status)
            val csrf = mapper.readTree(csrfResult.response.contentAsString)["token"].asText()
            val csrfCookie = csrfResult.response.getCookie("ULTISTATS_LOOPBACK_CSRF")!!
            val probe = context.getBean(Probe::class.java)
            val id = UUID.fromString("10000000-0000-0000-0000-000000000001")
            val rows = mapper.readTree(Path.of("docs/api/auth-surfaces-v1.json").toFile())["routes"].filter { it["owner"].asText() == "enforcement" }
            fun perform(method: String, path: String, login: AuthLoginResult?): MvcResult {
                val builder = request(org.springframework.http.HttpMethod.valueOf(method), path)
                    .cookie(csrfCookie).header("X-CSRF-TOKEN", csrf).contentType("application/json").content("{malformed")
                if (login != null) {
                    builder.cookie(Cookie("ULTISTATS_LOOPBACK_SESSION", login.credential))
                    builder.header("X-UltiStats-Installation-Id", login.context.installationId)
                    builder.header("X-UltiStats-Account-Id", login.context.account!!.id)
                    builder.header("X-UltiStats-Session-Id", login.context.sessionId)
                }
                return mvc.perform(builder).andReturn()
            }
            for (row in rows) {
                val method = row["method"].asText()
                val path = row["path"].asText().replace(Regex("\\{[^}]+}"), id.toString())
                val public = row["policy"].asText() == "PUBLIC"
                for ((name, login, expected) in listOf(Triple("guest", null, if (public) 200 else 401),
                    Triple("organizer", organizer, 200), Triple("admin", admin, 200), Triple("temp", temp, if (public) 200 else 403))) {
                    val count = probe.calls.get()
                    val result = perform(method, path, login)
                    assertEquals(expected, result.response.status, "$name $method $path")
                    assertEquals(count + if (expected == 200) 1 else 0, probe.calls.get(), "Denied call entered domain probe")
                    if (expected != 200) assertEquals(true, mapper.readTree(result.response.contentAsString)["requestRejectedBeforeExecution"].asBoolean())
                }
            }
            // These barriers run before CSRF can create a cookie or MVC can bind malformed JSON.
            for (row in rows.filter { it["method"].asText() !in setOf("GET", "HEAD") }) {
                val method = row["method"].asText()
                val path = row["path"].asText().replace(Regex("\\{[^}]+}"), id.toString())
                val names = DefaultAuthRequestAccess.headers
                val values = listOf(admin.context.installationId.toString(), admin.context.account!!.id.toString(), admin.context.sessionId.toString())
                for (scenario in listOf(-2, 0, 1, 2, 3, 4, 5)) {
                    val builder = request(org.springframework.http.HttpMethod.valueOf(method), path)
                        .cookie(Cookie("ULTISTATS_LOOPBACK_SESSION", admin.credential))
                        .contentType("application/json").content("{malformed")
                    names.forEachIndexed { index, name ->
                        if (scenario != -2 && scenario != index) builder.header(name,
                            if (scenario == index + 3) UUID.randomUUID().toString() else values[index])
                    }
                    val count = probe.calls.get()
                    val result = mvc.perform(builder).andReturn()
                    assertEquals(403, result.response.status, "capture scenario$scenario $method $path")
                    val code = if (scenario < 3) "AUTH_CONTEXT_REQUIRED" else "AUTH_IDENTITY_MISMATCH"
                    assertEquals(code, mapper.readTree(result.response.contentAsString)["code"].asText())
                    assertEquals(count, probe.calls.get(), "Denied owner reached domain")
                    assertEquals(emptyList(), result.response.getHeaders("Set-Cookie").toList(), "Stale owner materialized CSRF cookie")
                }
            }
            val matches = context.getBean(MatchService::class.java)
            Mockito.`when`(matches.get(id)).thenReturn(Match(id, emptyList(), endedAt = java.time.Instant.EPOCH))
            for (path in listOf("/api/v1/matches/$id/statistics", "/api/v1/matches/$id/statistics/export")) {
                assertEquals(200, perform("GET", path, null).response.status)
                assertEquals(200, perform("HEAD", path, null).response.status)
            }
            context.getBean(Mode::class.java).value = AuthEntryMode.ANONYMOUS
            for (row in rows.filter { it["policy"].asText() != "PUBLIC" }) {
                val method = row["method"].asText()
                val path = row["path"].asText().replace(Regex("\\{[^}]+}"), id.toString())
                if (row["policy"].asText().startsWith("FINISHED")) continue
                val count = probe.calls.get()
                val result = perform(method, path, admin)
                assertEquals(403, result.response.status, "Anonymous transport $method $path")
                assertEquals("PUBLIC_TRANSPORT_READ_ONLY", mapper.readTree(result.response.contentAsString)["code"].asText())
                assertEquals(count, probe.calls.get())
                assertEquals(emptyList(), result.response.getHeaders("Set-Cookie").toList())
            }
        }
    }
    @Test
    fun `account HTTP methods require admin and mutation stepup without changing target on denial`() {
        AnnotationConfigWebApplicationContext().use { context ->
            context.servletContext = MockServletContext()
            context.register(Config::class.java, AuthSecurityConfig::class.java, AuthController::class.java,
                AccountController::class.java, AuthExceptionHandler::class.java)
            context.refresh()
            val fixture = context.getBean(AuthTestFixture::class.java)
            val admin = fixture.activeLogin()
            val created = fixture.service.createAccount(admin.context.principal(), CreateAccountRequest("organizer", "Original", AuthRole.ORGANIZER))
            val temp = fixture.service.login(created.account.login, created.temporaryPassword, null)
            val second = fixture.service.createAccount(admin.context.principal(), CreateAccountRequest("active-organizer", "Organizer", AuthRole.ORGANIZER))
            val initial = fixture.service.login(second.account.login, second.temporaryPassword, null)
            fixture.service.changePassword(initial.context.principal(), second.temporaryPassword, "organizer-password")
            val organizer = fixture.service.login(second.account.login, "organizer-password", null)
            val mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity()).build()
            val mapper = context.getBean(ObjectMapper::class.java)
            val csrfResponse = mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/auth/csrf")).andReturn()
            val csrf = mapper.readTree(csrfResponse.response.contentAsString)["token"].asText()
            val cookie = csrfResponse.response.getCookie("ULTISTATS_LOOPBACK_CSRF")!!
            fun call(method: String, path: String, body: String, login: AuthLoginResult?): MvcResult {
                val builder = request(org.springframework.http.HttpMethod.valueOf(method), path).cookie(cookie)
                    .header("X-CSRF-TOKEN", csrf).contentType("application/json").content(body)
                if (login != null) builder.cookie(Cookie("ULTISTATS_LOOPBACK_SESSION", login.credential))
                    .header("X-UltiStats-Installation-Id", login.context.installationId)
                    .header("X-UltiStats-Account-Id", login.context.account!!.id)
                    .header("X-UltiStats-Session-Id", login.context.sessionId)
                return mvc.perform(builder).andReturn()
            }
            val operations = listOf(
                Triple("POST", "/api/v1/accounts", """{"login":"new-user","displayName":"New","role":"ORGANIZER"}"""),
                Triple("PATCH", "/api/v1/accounts/${created.account.id}", """{"displayName":"Changed"}"""),
                Triple("POST", "/api/v1/accounts/${created.account.id}/password-reset", "{}"),
            )
            for ((method, path, body) in operations) for ((login, expected) in listOf(null to 401, organizer to 403, temp to 403)) {
                val result = call(method, path, body, login)
                assertEquals(expected, result.response.status, "$method $path permission")
            }
            assertEquals(401, call("GET", "/api/v1/accounts", "", null).response.status)
            assertEquals(403, call("GET", "/api/v1/accounts", "", organizer).response.status)
            assertEquals(403, call("GET", "/api/v1/accounts", "", temp).response.status)
            assertEquals(200, call("GET", "/api/v1/accounts", "", admin).response.status)
            fixture.clock.now = fixture.clock.now.plusSeconds(301)
            val before = fixture.service.listAccounts(0, 100, null)
            for ((method, path, body) in operations) {
                val result = call(method, path, body, admin)
                assertEquals(403, result.response.status)
                assertEquals("AUTH_REAUTH_REQUIRED", mapper.readTree(result.response.contentAsString)["code"].asText())
            }
            assertEquals(before, fixture.service.listAccounts(0, 100, null))
            assertEquals(200, call("POST", "/api/v1/auth/reauth", """{"password":"permanent-password"}""", admin).response.status)
            for ((method, path, body) in operations) {
                val result = call(method, path, body, admin)
                assertEquals(if (method == "POST" && path == "/api/v1/accounts") 201 else 200, result.response.status,
                    "$method $path after step-up")
            }
        }
    }

}
