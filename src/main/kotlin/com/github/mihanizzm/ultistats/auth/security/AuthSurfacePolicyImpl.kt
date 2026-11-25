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
        if (exactSource.matches(path)) {
            return if (request.method == "GET") AuthRequirement.ObsSourceBearer else AuthRequirement.Deny
        }
        val method = if (request.method == "HEAD") "GET" else request.method
        if (method == "GET") {
            if (path in previewAssets) return AuthRequirement.Permission(AuthPermission.OBS_ADMIN, secureOnly = true)
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
        return rule.permission?.let { AuthRequirement.Permission(it, rule.secureOnly) } ?: AuthRequirement.PublicRead
    }

    private fun canonicalPath(path: String): Boolean = path.startsWith('/') &&
        listOf(';', '%', '\\').none { it in path } && !path.contains("//") &&
        path.split('/').none { it == "." || it == ".." }

    private data class Rule(val method: String, val pattern: Regex, val permission: AuthPermission?, val statistics: Boolean, val secureOnly: Boolean)
    private fun rule(method: String, path: String, permission: AuthPermission? = null, statistics: Boolean = false, secureOnly: Boolean = false) =
        Rule(method, Regex(path.replace(Regex("\\{[^}]+}"), "[^/]+")), permission, statistics, secureOnly)

    private val exactSource = Regex("/api/v1/obs/sources/(?:scoreboard|event-log|infographics)/[^/]+")

    private val rules = listOf(
        rule("POST", "/api/v1/imports/teams-players/csv", AuthPermission.SPORTS_WRITE),
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
        rule("GET", "/api/v1/matches/{matchId}/point-lineups"),
        rule("PUT", "/api/v1/matches/{matchId}/point-lineups/{pointId}", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/matches/{matchId}/point-lineups/{pointId}/slots/{slotId}", AuthPermission.SPORTS_WRITE),
        rule("GET", "/api/v1/matches/{matchId}/announcements"),
        rule("POST", "/api/v1/matches/{matchId}/announcements", AuthPermission.SPORTS_WRITE),
        rule("POST", "/api/v1/matches/{matchId}/announcements/{id}/end", AuthPermission.SPORTS_WRITE),
        rule("PATCH", "/api/v1/matches/{matchId}/announcements/{id}/team", AuthPermission.SPORTS_WRITE),
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
        rule("GET", "/api/v1/obs/infographics/leaders/{matchId}", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("GET", "/api/v1/obs/previews/infographics/{matchId}", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("GET", "/api/v1/obs/previews/scoreboard/{matchId}", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("GET", "/api/v1/obs/previews/event-log/{matchId}", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("GET", "/api/v1/obs/source-keys", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/source-keys/current", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("GET", "/api/v1/obs/source-keys/current/{matchId}", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("POST", "/api/v1/obs/source-keys", AuthPermission.OBS_ADMIN, secureOnly = true),
        rule("DELETE", "/api/v1/obs/source-keys/{keyId}", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/config", AuthPermission.OBS_ADMIN),
        rule("PUT", "/api/v1/obs/config", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/config/stream", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/stream", AuthPermission.OBS_ADMIN),
        rule("GET", "/api/v1/obs/scoreboard/{matchId}", AuthPermission.OBS_ADMIN),
        rule("PUT", "/api/v1/obs/scoreboard/{matchId}/profile", AuthPermission.OBS_ADMIN),
    )

    private val publicAssets = setOf(
        "/obs-sources/infographics.html", "/obs/infographics.mjs", "/obs/infographics-model.mjs", "/obs/infographics.css",
        "/obs-sources/scoreboard.html",
        "/obs-sources/event-log.html",
        "/obs/protected-event-log.mjs",
        "/obs/protected-event-log-model.mjs",
        "/obs/protected-source-client.mjs",
        "/obs/protected-scoreboard.mjs",
        "/obs/colors.mjs",
        "/obs/feed.mjs",
        "/obs/obs.css",
        "/obs/renderer.mjs",
        "/obs/scoreboard-model.mjs",
        "/obs/scoreboard-renderer.mjs",
        "/obs/scoreboard.css",
        "/obs-diagnostic.html", "/obs-battle-log.html", "/obs-scoreboard.html",
    )
    private val previewAssets = setOf(
        "/obs-leaders.html", "/obs/leaders.mjs", "/obs/leaders.css",
        "/obs-preview/infographics.html", "/obs-preview/scoreboard.html", "/obs-preview/event-log.html", "/obs/staff-preview.mjs",
        "/obs-editor/scoreboard.html", "/obs-editor/event-log.html", "/obs/staff-reader.mjs",
    )
    private val obsStaffAssets = setOf(
        "/obs/client.mjs", "/obs/overlay.mjs", "/obs/scoreboard-client.mjs", "/obs/scoreboard.mjs", "/obs/shared-worker.js", "/obs/transport.mjs",
        "/obs-admin.html", "/obs/admin.mjs", "/obs/scoreboard-admin.mjs", "/obs/active-links.mjs", "/obs/active-links-admin.mjs",
        "/auth/obs-session.mjs", "/auth/staff-session.mjs", "/auth/public-sources.mjs",
    )
    private val docsAssets = setOf("/auth/swagger.html", "/auth/swagger.mjs", "/auth/swagger-adapter.mjs")
}
