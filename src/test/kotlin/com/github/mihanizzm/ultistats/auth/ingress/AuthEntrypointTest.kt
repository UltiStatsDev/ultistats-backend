package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import java.net.Socket

class AuthEntrypointTest {
    @Test
    fun `actual anonymous socket cannot be upgraded by client headers`() {
        withServer(AuthIngressProperties(privatePort = 0)) { publicPort, privatePort, _ ->
            val forged = "Host: staff.example.test\r\nX-Forwarded-Proto: https\r\nX-Forwarded-Port: $privatePort\r\n" +
                "Forwarded: for=127.0.0.1;proto=https;host=staff.example.test\r\n" +
                "Cookie: __Host-ULTISTATS_SESSION=forged\r\nAuthorization: Bearer forged\r\nOrigin: https://staff.example.test\r\n"
            assertEquals("ANONYMOUS", request(publicPort, forged))
            assertEquals("SECURE", request(privatePort, "Host: public.example.test\r\nX-Forwarded-Proto: http\r\n"))
        }
    }

    @Test
    fun `loopback HTTP requires explicit mode and has no second private connector`() {
        withServer(AuthIngressProperties(loopbackMode = true, publicOrigin = "http://localhost:8080",
            secureOrigin = "http://localhost:8080", privatePort = 0)) { port, privatePort, _ ->
            assertEquals(-1, privatePort)
            assertEquals("LOOPBACK", request(port, "Host: attacker.example.test\r\n"))
        }
    }

    @Test
    fun `unknown socket and forged servlet scheme remain anonymous`() {
        val classifier = ListenerAuthEntryClassifier(AuthIngressProperties())
        val request = MockHttpServletRequest().apply {
            localPort = 443; localAddr = "127.0.0.1"; remoteAddr = "127.0.0.1"
            isSecure = true; scheme = "https"
            addHeader("X-Forwarded-Proto", "https")
        }
        assertEquals(AuthEntryMode.ANONYMOUS, classifier.classify(request))
    }

    @Test
    fun `forwarding strategy is rejected instead of trusting remote headers`() {
        val properties = AuthIngressProperties()
        val config = AuthConnectorConfiguration(properties, MockEnvironment().withProperty("server.forward-headers-strategy", "framework"))
        assertThrows(IllegalArgumentException::class.java) {
            config.connectorCustomizer(config.entryClassifier()).customize(TomcatServletWebServerFactory())
        }
    }

    private fun withServer(properties: AuthIngressProperties, action: (Int, Int, ListenerAuthEntryClassifier) -> Unit) {
        val config = AuthConnectorConfiguration(properties, MockEnvironment())
        val classifier = config.entryClassifier()
        val factory = TomcatServletWebServerFactory(0)
        config.connectorCustomizer(classifier).customize(factory)
        val server = factory.getWebServer({ context ->
            context.addServlet("probe", object : HttpServlet() {
                override fun doGet(request: HttpServletRequest, response: HttpServletResponse) {
                    response.writer.write(classifier.classify(request).name)
                }
            }).addMapping("/*")
        })
        try {
            server.start()
            val connectors = (server as org.springframework.boot.web.embedded.tomcat.TomcatWebServer).tomcat.service.findConnectors()
            assertTrue(connectors.all { (it.getProperty("address") as java.net.InetAddress).isLoopbackAddress })
            action(server.port, connectors.firstOrNull { it.localPort != server.port }?.localPort ?: -1, classifier)
        } finally { server.stop(); server.destroy() }
    }

    private fun request(port: Int, headers: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET /probe HTTP/1.0\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n").trim()
    }
}
