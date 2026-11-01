package com.github.mihanizzm.ultistats.auth.ingress

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthIngressPropertiesTest {
    @Test
    fun `rejects origins containing credentials paths query fragment or non-network schemes`() {
        for (origin in listOf("https://user:secret@localhost", "https://localhost/path", "https://localhost/",
            "https://localhost?x=1", "https://localhost#login", "file:///tmp/login", "https://*.example.test",
            "https://localhost:0", "https://localhost:65536")) {
            assertThrows(IllegalArgumentException::class.java, { AuthIngressProperties(secureOrigin = origin).validate() }, origin)
        }
    }

    @Test
    fun `network private origin requires HTTPS and loopback HTTP is explicit`() {
        assertThrows(IllegalArgumentException::class.java) {
            AuthIngressProperties(secureOrigin = "http://localhost:8080").validate()
        }
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            val properties = AuthIngressProperties(loopbackMode = true,
                publicOrigin = "http://$host:8080", secureOrigin = "http://$host:8080")
            properties.validate()
            assertEquals(setOf("http://$host:8080"), properties.secureOrigins())
        }
    }

    @Test
    fun `loopback cannot bind or advertise a network address`() {
        for (address in listOf("0.0.0.0", "::", "192.0.2.1", "localhost")) {
            assertThrows(IllegalArgumentException::class.java) {
                AuthIngressProperties(loopbackMode = true, anonymousAddress = address).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthIngressProperties(loopbackMode = true, publicOrigin = "http://192.0.2.1:8080").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthIngressProperties(loopbackMode = true, secureOrigin = "https://staff.example.test").validate()
        }
    }

    @Test
    fun `configured default ports and host case use browser serialized origins`() {
        val properties = AuthIngressProperties(publicOrigin = "http://GUEST.EXAMPLE.TEST:80",
            secureOrigin = "https://STAFF.EXAMPLE.TEST:443")
        properties.validate()
        assertEquals("http://guest.example.test", properties.publicOrigin())
        assertEquals("https://staff.example.test", properties.secureOrigin())
        assertEquals(setOf("https://staff.example.test"), properties.secureOrigins())
    }

    @Test
    fun `LAN and Internet origins are configured unchanged and private port bounded`() {
        val lan = AuthIngressProperties(anonymousAddress = "0.0.0.0",
            publicOrigin = "http://192.0.2.1", secureOrigin = "https://staff.example.test")
        lan.validate()
        assertEquals("http://192.0.2.1", lan.publicOrigin())
        assertEquals("https://staff.example.test", lan.secureOrigin())
        assertEquals(setOf("https://staff.example.test"), lan.secureOrigins())
        AuthIngressProperties(publicOrigin = "https://stats.example.test", secureOrigin = "https://stats.example.test").validate()
        for (port in listOf(-1, 65536)) {
            assertThrows(IllegalArgumentException::class.java) { AuthIngressProperties(privatePort = port).validate() }
        }
    }
}
