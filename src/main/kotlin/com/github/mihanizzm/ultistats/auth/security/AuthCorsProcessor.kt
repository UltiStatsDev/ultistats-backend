package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsProcessor
import org.springframework.web.cors.DefaultCorsProcessor

/** Keeps Spring's CORS checks, replacing only its plaintext denial response. */
class AuthCorsProcessor(
    private val writer: AuthProblemWriter,
    private val classifier: AuthEntryClassifier?,
    private val policy: AuthSurfacePolicy?,
) : CorsProcessor {
    override fun processRequest(configuration: CorsConfiguration?, request: HttpServletRequest, response: HttpServletResponse): Boolean =
        object : DefaultCorsProcessor() {
            override fun rejectRequest(serverResponse: ServerHttpResponse) {
                val publicRead = request.method in setOf("GET", "HEAD") &&
                    (request.requestURI.removePrefix(request.contextPath) in setOf("/api/v1/installation", "/health/ready") ||
                        policy?.requirement(request) == AuthRequirement.PublicRead)
                // A forged Origin must not change an anonymous private-path transport denial.
                val anonymous = (classifier?.classify(request) ?: AuthEntryMode.ANONYMOUS) == AuthEntryMode.ANONYMOUS
                val code = if (anonymous && !publicRead) "PUBLIC_TRANSPORT_READ_ONLY" else "CSRF_INVALID"
                writer.write(request, response, AuthProblem(403, code))
            }
        }.processRequest(configuration, request, response)
}
