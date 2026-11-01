package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class AuthClientSourceTest {
    @Test
    fun `public and loopback sources ignore dedicated and forwarded headers`() {
        for (mode in listOf(AuthEntryMode.ANONYMOUS, AuthEntryMode.LOOPBACK)) {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "192.0.2.9"
                addHeader("X-UltiStats-Client-IP", "198.51.100.42")
                addHeader("X-Forwarded-For", "198.51.100.43")
                addHeader("Forwarded", "for=198.51.100.44")
            }
            assertEquals("192.0.2.9", source(mode).source(request))
        }
    }

    @Test
    fun `secure trusted peer uses distinct proxy overwritten addresses not XFF`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("X-UltiStats-Client-IP", "192.0.2.10")
            addHeader("X-Forwarded-For", "198.51.100.99")
        }
        assertEquals("192.0.2.10", source(AuthEntryMode.SECURE).source(request))
        request.removeHeader("X-UltiStats-Client-IP")
        request.addHeader("X-UltiStats-Client-IP", "2001:db8::1")
        assertEquals("2001:db8:0:0:0:0:0:1", source(AuthEntryMode.SECURE).source(request))
    }

    @Test
    fun `secure source rejects missing malformed multiple or nonloopback peer`() {
        for (value in listOf(null, "", "example.test", "127.1", "192.0.2.999", "192.0.2.1, 192.0.2.2",
            "192.0.2.1:1234", "::1%lo", "::g", " 192.0.2.1 ")) {
            val request = MockHttpServletRequest().apply {
                remoteAddr = "127.0.0.1"
                if (value != null) addHeader("X-UltiStats-Client-IP", value)
            }
            val problem = assertThrows(AuthProblem::class.java, { source(AuthEntryMode.SECURE).source(request) }, value)
            assertEquals(403, problem.status)
            assertEquals("AUTH_PERMISSION_DENIED", problem.code)
        }
        val duplicate = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("X-UltiStats-Client-IP", "192.0.2.1")
            addHeader("X-UltiStats-Client-IP", "192.0.2.2")
        }
        assertThrows(AuthProblem::class.java) { source(AuthEntryMode.SECURE).source(duplicate) }
        duplicate.remoteAddr = "192.0.2.1"
        assertThrows(AuthProblem::class.java) { source(AuthEntryMode.SECURE).source(duplicate) }
    }

    private fun source(mode: AuthEntryMode) = ListenerAuthClientSource(object : AuthEntryClassifier {
        override fun classify(request: jakarta.servlet.http.HttpServletRequest) = mode
    })
}
