package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.Collections

@Component
class ListenerAuthClientSource(private val classifier: AuthEntryClassifier) : AuthClientSource {
    override fun source(request: HttpServletRequest): String {
        if (classifier.classify(request) != AuthEntryMode.SECURE) return request.remoteAddr
        if (request.remoteAddr !in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")) denied()
        val values = Collections.list(request.getHeaders("X-UltiStats-Client-IP"))
        if (values.size != 1) denied()
        val value = values.single()
        return try {
            when {
                IPV4.matches(value) -> {
                    val bytes = value.split('.').map { it.toInt() }
                    if (bytes.any { it !in 0..255 }) denied()
                    InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray()).hostAddress
                }
                ':' in value && IPV6.matches(value) -> InetAddress.getByName(value).hostAddress
                else -> denied()
            }
        } catch (_: java.net.UnknownHostException) { denied() }
    }

    private fun denied(): Nothing = throw AuthProblem(403, "AUTH_PERMISSION_DENIED")

    companion object {
        private val IPV4 = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
        private val IPV6 = Regex("[0-9a-fA-F:]{2,45}")
    }
}
