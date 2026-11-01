package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.mihanizzm.ultistats.auth.security.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.*

class AuthHtmlNavigationHookTest {
    private val mapper=jacksonObjectMapper()
    private fun writer()=AuthProblemWriter(mapper,object:AuthHtmlEntryNavigation {
        override fun redirect(request:jakarta.servlet.http.HttpServletRequest,response:jakarta.servlet.http.HttpServletResponse,problem:AuthProblem):Boolean {
            response.status=303;response.setHeader("Location","https://staff.example/login?return=test");return true
        }
    })
    @Test fun `eligible HTML document auth denial can navigate without cookie clearing`() {
        val request=MockHttpServletRequest("GET","/obs-admin.html").apply { addHeader("Accept","text/html") }
        val response=MockHttpServletResponse()
        writer().write(request,response,AuthProblem(401,"AUTH_REQUIRED"))
        assertEquals(303,response.status)
        assertEquals("https://staff.example/login?return=test",response.getHeader("Location"))
        assertTrue(response.getHeaders("Set-Cookie").isEmpty())
        assertEquals("no-store",response.getHeader("Cache-Control"))
    }
    @Test fun `API assets captured identity wrong methods and nonHTML retain raw problem semantics`() {
        val cases=listOf(
            Triple("GET","/api/v1/teams","text/html"),Triple("GET","/v3/api-docs","text/html"),
            Triple("GET","/auth/staff-session.mjs","text/html"),Triple("POST","/obs-admin.html","text/html"),
            Triple("GET","/obs-admin.html","application/json"),Triple("GET","/obs-admin.html","text/html;q=0"))
        for((method,path,accept) in cases) {
            val response=MockHttpServletResponse()
            writer().write(MockHttpServletRequest(method,path).apply { addHeader("Accept",accept) },response,AuthProblem(401,"AUTH_REQUIRED"))
            assertEquals(401,response.status)
            assertNull(response.getHeader("Location"))
            assertEquals("AUTH_REQUIRED",mapper.readTree(response.contentAsString)["code"].asText())
        }
        for(problem in listOf(AuthProblem(403,"AUTH_IDENTITY_MISMATCH"),AuthProblem(403,"PUBLIC_TRANSPORT_READ_ONLY"))) {
            val response=MockHttpServletResponse()
            writer().write(MockHttpServletRequest("GET","/obs-admin.html").apply { addHeader("Accept","text/html") },response,problem)
            assertEquals(403,response.status);assertNull(response.getHeader("Location"))
        }
        val response=MockHttpServletResponse()
        writer().write(MockHttpServletRequest("GET","/obs-admin.html").apply {
            addHeader("Accept","text/html");addHeader("X-UltiStats-Installation-Id","captured")
        },response,AuthProblem(401,"AUTH_SESSION_REVOKED"))
        assertEquals(401,response.status);assertNull(response.getHeader("Location"))
    }
}
