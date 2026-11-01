package com.github.mihanizzm.ultistats.auth.ingress

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("app.auth.ingress")
data class AuthIngressProperties(
    val publicOrigin: String = "http://localhost:8080",
    val secureOrigin: String = "https://localhost:8443",
    val loopbackMode: Boolean = false,
    val anonymousAddress: String = "127.0.0.1",
    val privatePort: Int = 8081,
) : AuthOriginConfiguration {
    override fun publicOrigin(): String = publicOrigin
    override fun secureOrigin(): String = secureOrigin
    override fun secureOrigins(): Set<String> = setOf(secureOrigin)

    fun validate() {
        val public = origin(publicOrigin)
        val secure = origin(secureOrigin)
        require(privatePort in 0..65535) { "private-port must be in 0..65535" }
        // Numeric binds only: configuration must not trigger DNS resolution.
        require(anonymousAddress in setOf("127.0.0.1", "::1", "0.0.0.0", "::")) {
            "anonymous-address must be an explicit loopback or wildcard IP"
        }
        require(secure.scheme == "https" || (loopbackMode && secure.scheme == "http" && loopbackHost(secure.host))) {
            "secure-origin requires HTTPS except explicit loopback HTTP mode"
        }
        if (loopbackMode) {
            require(anonymousAddress == "127.0.0.1" || anonymousAddress == "::1") { "loopback mode requires a loopback bind" }
            require(loopbackHost(public.host) && loopbackHost(secure.host)) { "loopback mode requires loopback origins" }
        }
    }

    private fun origin(value: String): URI {
        val uri = try { URI(value) } catch (e: Exception) { throw IllegalArgumentException("invalid configured origin", e) }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "configured origin must contain only HTTP(S) scheme, host and optional port" }
        return uri
    }

    private fun loopbackHost(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
}
