package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.service.MatchService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.util.UUID

/** Explicit allowlist: adding a controller mapping does not silently grant guest access. */
@Component
class AuthSurfacePolicyImpl(private val matches: MatchService) : AuthSurfacePolicy {
    override fun requirement(request: HttpServletRequest): AuthRequirement {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (!canonicalPath(path)) return AuthRequirement.Deny
        val method = if (request.method == "HEAD") "GET" else request.method
        if (method == "GET") {
            if (path == "/obs-battle-log.html" || path == "/obs-scoreboard.html") {
                return if (request.getParameterValues("preview")?.any { it == "1" } == true)
                    AuthRequirement.Permission(AuthPermission.OBS_ADMIN) else AuthRequirement.PublicRead
            }
            if (path in obsStaffAssets) return AuthRequirement.Permission(AuthPermission.OBS_ADMIN)
            if (path in docsAssets || path == "/swagger-ui.html" || path.startsWith("/swagger-ui/") ||
                path == "/v3/api-docs" || path.startsWith("/v3/api-docs/") || path == "/v3/api-docs.yaml")
                return AuthRequirement.Permission(AuthPermission.API_DOCS)
            if (path in publicAssets || path.startsWith("/uploads/")) return AuthRequirement.PublicRead
        }
        val rule = rules.firstOrNull { it.method == method && it.pattern.matches(path) }
            ?: return AuthRequirement.Deny
        if (rule.statistics) {
            val id = path.split('/')[4].let { runCatching { UUID.fromString(it) }.getOrNull() }
            // Missing/deleted/invalid identifiers can never become a public FINISHED default.
            return if (id != null && matches.get(id)?.let { it.deletedAt == null && it.status == MatchStatus.FINISHED } == true)
                AuthRequirement.PublicRead else AuthRequirement.Permission(AuthPermission.STATISTICS_READ_ALL)
        }
        return rule.permission?.let { AuthRequirement.Permission(it) } ?: AuthRequirement.PublicRead
    }

    private fun canonicalPath(path: String): Boolean = path.startsWith('/') &&
        listOf(';', '%', '\\').none { it in path } && !path.contains("//") &&
        path.split('/').none { it == "." || it == ".." }

    private data class Rule(val method: String, val pattern: Regex, val permission: AuthPermission?, val statistics: Boolean)
    private fun rule(method: String, path: String, permission: AuthPermission? = null, statistics: Boolean = false) =
        Rule(method, Regex(path.replace(Regex("\\{[^}]+}"), "[^/]+")), permission, statistics)

    private val rules = listOf(
        rule("GET", "/api/v2/matches/{matchId}/events"),
        rule("POST", "/api/v2/matches/{matchId}/events/batch", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/matches/{matchId}/events"),
        rule("GET", "/api/v1/matches/{matchId}/events/{eventId}"),
        rule("POST", "/api/v1/matches/{matchId}/events", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/matches/{matchId}/events/{eventId}", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/matches/{matchId}/events/undo", AuthPermission.SPORTS_WRITE),
        rule("DELETE", "/api/v1/matches/{matchId}/events/{eventId}", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/matches"),
        rule("GET", "/api/v1/matches/{id}"),
        rule("POST", "/api/v1/matches", AuthPermission.SPORTS_WRITE),
        rule("PUT", "/api/v1/matches/{id}", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/matches/{id}", AuthPermission.SPORTS_WRITE),
        rule("DELETE", "/api/v1/matches/{id}", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/matches/{id}/start", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/matches/{id}/end", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/matches/{matchId}/events/stream"),
        rule("POST", "/api/v1/matches/export", AuthPermission.TRANSFER),
        rule("POST", "/api/v1/matches/import", AuthPermission.TRANSFER),
        rule("GET", "/api/v1/players"),
        rule("GET", "/api/v1/players/{id}"),
        rule("GET", "/api/v1/players/{playerId}/teams"),
        rule("POST", "/api/v1/players", AuthPermission.SPORTS_WRITE),
        rule("PUT", "/api/v1/players/{id}", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/players/{id}", AuthPermission.SPORTS_WRITE),
        rule("DELETE", "/api/v1/players/{id}", AuthPermission.SPORTS_WRITE),
        rule("PUT", "/api/v1/players/{playerId}/photo", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/players/{playerId}/photo"),
        rule("DELETE", "/api/v1/players/{playerId}/photo", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/players/{playerId}/uploadPhoto", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/players/{playerId}/photoUrl"),
        rule("DELETE", "/api/v1/players/{playerId}/photoUrl", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/matches/{matchId}/statistics", statistics = true),
        rule("GET", "/api/v1/matches/{matchId}/statistics/export", statistics = true),
        rule("GET", "/api/v1/teams"),
        rule("GET", "/api/v1/teams/{id}"),
        rule("POST", "/api/v1/teams", AuthPermission.SPORTS_WRITE),
        rule("PUT", "/api/v1/teams/{id}", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/teams/{id}", AuthPermission.SPORTS_WRITE),
        rule("DELETE", "/api/v1/teams/{id}", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/teams/{teamId}/players"),
        rule("PUT", "/api/v1/teams/{teamId}/players/{playerId}", AuthPermission.SPORTS_WRITE),
        rule("DELETE", "/api/v1/teams/{teamId}/players/{playerId}", AuthPermission.SPORTS_WRITE),
        rule("PUT", "/api/v1/teams/{teamId}/photo", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/teams/{teamId}/photo"),
        rule("DELETE", "/api/v1/teams/{teamId}/photo", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/teams/{teamId}/uploadPhoto", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/teams/{teamId}/photoUrl"),
        rule("DELETE", "/api/v1/teams/{teamId}/photoUrl", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v2/matches/{matchId}/events/undo", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v2/matches/{matchId}/events/undo/{commandId}", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/obs/config"),
        rule("PUT", "/api/v1/obs/config", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/config/stream"),
        rule("GET", "/api/v1/obs/stream"),
        rule("GET", "/api/v1/obs/scoreboard/{matchId}"),
        rule("PUT", "/api/v1/obs/scoreboard/{matchId}/profile", AuthPermission.OBS_ADMIN),
    )

    private val publicAssets = setOf(
        "/obs/client.mjs",
        "/obs/colors.mjs",
        "/obs/feed.mjs",
        "/obs/obs.css",
        "/obs/overlay.mjs",
        "/obs/renderer.mjs",
        "/obs/scoreboard-client.mjs",
        "/obs/scoreboard-model.mjs",
        "/obs/scoreboard-renderer.mjs",
        "/obs/scoreboard.css",
        "/obs/scoreboard.mjs",
        "/obs/shared-worker.js",
        "/obs/transport.mjs",
        "/obs-diagnostic.html",
    )
    private val obsStaffAssets = setOf(
        "/obs-admin.html", "/obs/admin.mjs", "/obs/scoreboard-admin.mjs",
        "/auth/obs-session.mjs", "/auth/staff-session.mjs",
    )
    private val docsAssets = setOf("/auth/swagger.html", "/auth/swagger.mjs", "/auth/swagger-adapter.mjs")
}
