package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Only native staff documents navigate. No request-provided URL/query becomes a login target. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class StaffHtmlEntryNavigation(
    private val origins: AuthOriginConfiguration,
    private val classifier: AuthEntryClassifier,
) : AuthHtmlEntryNavigation {
    override fun redirect(request: HttpServletRequest, response: HttpServletResponse, problem: AuthProblem): Boolean {
        val mode = classifier.classify(request)
        if (mode == AuthEntryMode.ANONYMOUS || request.method != "GET") return false
        if (request.getHeader("X-Requested-With")?.equals("XMLHttpRequest", ignoreCase = true) == true) return false
        val fetchMode = request.getHeader("Sec-Fetch-Mode")
        if (fetchMode != null && !fetchMode.equals("navigate", ignoreCase = true)) return false
        val canonical = when (request.requestURI.removePrefix(request.contextPath)) {
            "/obs-admin.html" -> "/obs-admin.html"
            "/swagger-ui.html", "/swagger-ui/index.html" -> "/swagger-ui.html"
            else -> return false
        }
        val target = runCatching { URI(origins.secureOrigin()) }.getOrNull() ?: return false
        val valid = target.host != null && target.rawUserInfo == null && target.rawPath.isNullOrEmpty() &&
            target.rawQuery == null && target.rawFragment == null &&
            (target.port == -1 || target.port in 1..65535) &&
            (target.scheme == "https" || (mode == AuthEntryMode.LOOPBACK && target.scheme == "http" &&
                target.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")))
        if (!valid) return false
        val passwordChange = problem.status == 403 && problem.code == "AUTH_PASSWORD_CHANGE_REQUIRED"
        if (!passwordChange && (problem.status != 401 || problem.code !in setOf(
                "AUTH_REQUIRED", "AUTH_SESSION_INVALID", "AUTH_SESSION_EXPIRED", "AUTH_SESSION_REVOKED"))) return false
        val entry = if (passwordChange) "/account/password" else "/login"
        val encoded = URLEncoder.encode(canonical, StandardCharsets.UTF_8)
        response.status = HttpServletResponse.SC_FOUND
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Location", "${target.toASCIIString()}$entry?returnTo=$encoded")
        return true
    }
}
