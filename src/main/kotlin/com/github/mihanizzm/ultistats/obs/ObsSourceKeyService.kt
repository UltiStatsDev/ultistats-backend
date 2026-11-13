package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.auth.service.AuthValidation
import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

enum class ObsSourceKeyScope { EXACT_SCOREBOARD }

data class ObsSourceKeyMetadata(
    val keyId: UUID,
    val matchId: UUID,
    val scope: ObsSourceKeyScope,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

class IssuedObsSourceKey(
    @get:JsonUnwrapped val metadata: ObsSourceKeyMetadata,
    @get:Schema(description = "One-time opaque bearer credential. Keep it private.", format = "password")
    val secret: String,
)

@Service
class ObsSourceKeyService(
    private val jdbc: JdbcTemplate,
    private val scoreboard: ObsScoreboardService,
    private val clock: Clock,
) {
    @Transactional
    fun issue(matchId: UUID): IssuedObsSourceKey {
        // Keep the match active through key issuance by locking the row until commit.
        val exists = jdbc.query(
            "SELECT id FROM matches WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId,
        ).isNotEmpty()
        if (!exists) throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")

        val metadata = ObsSourceKeyMetadata(
            UUID.randomUUID(), matchId, ObsSourceKeyScope.EXACT_SCOREBOARD, clock.instant().truncatedTo(ChronoUnit.MICROS), null,
        )
        val secret = AuthValidation.secret()
        jdbc.update(
            """
                INSERT INTO obs_source_keys (key_id, match_id, scope, secret_digest, created_at)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            metadata.keyId, matchId, metadata.scope.name, digest(secret), Timestamp.from(metadata.createdAt),
        )
        return IssuedObsSourceKey(metadata, secret)
    }

    @Transactional(readOnly = true)
    fun list(): List<ObsSourceKeyMetadata> = jdbc.query(
        """
            SELECT key_id, match_id, scope, created_at, revoked_at
            FROM obs_source_keys ORDER BY created_at DESC, key_id
        """.trimIndent(),
    ) { rs, _ ->
        ObsSourceKeyMetadata(
            rs.getObject("key_id", UUID::class.java),
            rs.getObject("match_id", UUID::class.java),
            ObsSourceKeyScope.valueOf(rs.getString("scope")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("revoked_at")?.toInstant(),
        )
    }

    @Transactional
    fun revoke(keyId: UUID) {
        val updated = jdbc.update(
            "UPDATE obs_source_keys SET revoked_at = ? WHERE key_id = ? AND revoked_at IS NULL",
            Timestamp.from(clock.instant()), keyId,
        )
        if (updated == 0 && jdbc.queryForObject(
                "SELECT COUNT(*) FROM obs_source_keys WHERE key_id = ?", Long::class.java, keyId,
            ) == 0L
        ) throw AuthProblem(404, "OBS_SOURCE_KEY_NOT_FOUND")
    }

    // The key lookup establishes the same database snapshot used by the scoreboard.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun snapshot(matchId: UUID, authorization: List<String>): ObsExactScoreboardSnapshot {
        val header = authorization.singleOrNull()
        val secret = header?.let(bearer::matchEntire)?.groupValues?.get(1) ?: invalidKey()
        val boundMatchId = jdbc.query(
            "SELECT match_id FROM obs_source_keys WHERE secret_digest = ? AND revoked_at IS NULL AND scope = ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, digest(secret), ObsSourceKeyScope.EXACT_SCOREBOARD.name,
        ).singleOrNull() ?: invalidKey()
        if (boundMatchId != matchId) throw AuthProblem(403, "OBS_SOURCE_SCOPE_DENIED")
        return try {
            scoreboard.exactSnapshot(matchId)
        } catch (_: EntityNotFoundException) {
            throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        }
    }

    private fun invalidKey(): Nothing = throw AuthProblem(401, "OBS_SOURCE_KEY_REQUIRED")

    private fun digest(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII)))

    private companion object {
        val bearer = Regex("Bearer +([A-Za-z0-9_-]{43})", RegexOption.IGNORE_CASE)
    }
}
