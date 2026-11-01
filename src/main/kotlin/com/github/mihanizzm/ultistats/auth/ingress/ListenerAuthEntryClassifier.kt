package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import jakarta.servlet.http.HttpServletRequest
import org.apache.catalina.connector.Connector
import java.util.concurrent.ConcurrentHashMap

/** Connector identities are registered by server configuration, never by requests. */
class ListenerAuthEntryClassifier(@Suppress("UNUSED_PARAMETER") properties: AuthIngressProperties) : AuthEntryClassifier {
    private val connectors = ConcurrentHashMap<Connector, AuthEntryMode>()

    internal fun register(connector: Connector, mode: AuthEntryMode) {
        connectors[connector] = mode
    }

    override fun classify(request: HttpServletRequest): AuthEntryMode {
        val mode = connectors.entries.firstOrNull { it.key.localPort > 0 && it.key.localPort == request.localPort }?.value
            ?: return AuthEntryMode.ANONYMOUS
        if (mode != AuthEntryMode.ANONYMOUS &&
            (request.localAddr !in LOOPBACK_ADDRESSES || request.remoteAddr !in LOOPBACK_ADDRESSES)) {
            return AuthEntryMode.ANONYMOUS
        }
        return mode
    }

    companion object {
        private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    }
}
