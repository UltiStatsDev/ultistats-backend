package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.auth.repository.AuthStore
import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.ResultSet
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

enum class ObsCurrentSourceKeyStatus { ACTIVE, REISSUE_REQUIRED, NO_ACTIVE_LINK }

@JsonInclude(JsonInclude.Include.ALWAYS)
class ObsCurrentSourceKey(
    val status: ObsCurrentSourceKeyStatus,
    val key: ObsSourceKeyMetadata?,
    @get:Schema(description = "Protected Browser Source URL. Its fragment contains the bearer. Keep it private.", format = "uri")
    val url: String?,
)

@Service
class ObsSourceKeyService internal constructor(
    private val jdbc: JdbcTemplate,
    private val scoreboard: ObsScoreboardService,
    private val clock: Clock,
    private val authStore: AuthStore,
    private val origins: AuthOriginConfiguration,
    private val derivation: ObsSourceKeyDerivation,
) {
    @Transactional
    fun rotate(matchId: UUID, operationId: UUID): ObsCurrentSourceKey {
        // Serialize first pin and operation-ID resolution across scopes and instances.
        val pinned = jdbc.queryForObject(
            "SELECT fingerprint FROM obs_source_key_material WHERE singleton = 1 FOR UPDATE", String::class.java,
        )
        val previous = findKey("operation_id = ? FOR UPDATE", operationId)
        val scope = ObsSourceKeyScope.EXACT_SCOREBOARD
        if (previous != null && (previous.metadata.matchId != matchId || previous.metadata.scope != scope ||
                previous.metadata.revokedAt != null)) {
            throw AuthProblem(409, "OBS_SOURCE_OPERATION_CONFLICT")
        }
        requireMatch(matchId, lock = true)

        val material = derivation.load(authStore.installationId())
        if (previous != null) return restore(previous, material, pinned)
        val fingerprint = material.fingerprint()
        if (pinned != null && !matchesDigest(pinned, fingerprint)) keyMaterialUnavailable()

        val metadata = ObsSourceKeyMetadata(
            UUID.randomUUID(), matchId, scope, clock.instant().truncatedTo(ChronoUnit.MICROS), null,
        )
        val version = ObsSourceKeyDerivation.VERSION
        val secret = material.secret(metadata, version)
        val response = activeLink(metadata, secret)
        val createdAt = Timestamp.from(metadata.createdAt)
        try {
            if (pinned == null) {
                jdbc.update("UPDATE obs_source_key_material SET fingerprint = ? WHERE singleton = 1", fingerprint)
            }
            jdbc.update(
                "UPDATE obs_source_keys SET revoked_at = ? WHERE match_id = ? AND scope = ? AND revoked_at IS NULL",
                createdAt, matchId, scope.name,
            )
            jdbc.update(
                """
                    INSERT INTO obs_source_keys (key_id, match_id, scope, secret_digest, created_at, operation_id, derivation_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                metadata.keyId, matchId, scope.name, digest(secret), createdAt, operationId, version,
            )
        } catch (_: DataAccessException) {
            // PostgreSQL errors can include stored values. Roll back with a fixed public code.
            throw AuthProblem(503, "OBS_SOURCE_ROTATION_UNAVAILABLE")
        }
        return response
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun current(matchId: UUID): ObsCurrentSourceKey {
        requireMatch(matchId)
        val current = findKey("match_id = ? AND scope = ? AND revoked_at IS NULL", matchId, ObsSourceKeyScope.EXACT_SCOREBOARD.name)
            ?: return ObsCurrentSourceKey(ObsCurrentSourceKeyStatus.NO_ACTIVE_LINK, null, null)
        if (current.version == null) return ObsCurrentSourceKey(ObsCurrentSourceKeyStatus.REISSUE_REQUIRED, current.metadata, null)

        val pinned = jdbc.queryForObject(
            "SELECT fingerprint FROM obs_source_key_material WHERE singleton = 1", String::class.java,
        )
        return restore(current, derivation.load(authStore.installationId()), pinned)
    }

    private fun restore(current: StoredKey, material: ObsSourceKeyDerivation.Material, pinned: String?): ObsCurrentSourceKey {
        if (pinned == null || !matchesDigest(pinned, material.fingerprint())) keyMaterialUnavailable()
        val secret = material.secret(current.metadata, current.version ?: keyMaterialUnavailable())
        if (!matchesDigest(current.digest, digest(secret))) keyMaterialUnavailable()
        return activeLink(current.metadata, secret)
    }

    private fun activeLink(metadata: ObsSourceKeyMetadata, secret: String): ObsCurrentSourceKey {
        val origin = origins.secureOrigin()
        if (!origin.startsWith("https://")) keyMaterialUnavailable()
        val url = "$origin/obs-sources/scoreboard.html?matchId=${metadata.matchId}#key=$secret"
        return ObsCurrentSourceKey(ObsCurrentSourceKeyStatus.ACTIVE, metadata, url)
    }

    private fun requireMatch(matchId: UUID, lock: Boolean = false) {
        val query = "SELECT id FROM matches WHERE id = ? AND deleted_at IS NULL" + if (lock) " FOR UPDATE" else ""
        if (jdbc.query(query, { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId).isEmpty()) {
            throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        }
    }

    private class StoredKey(val metadata: ObsSourceKeyMetadata, val digest: String, val version: Int?)

    private fun findKey(predicate: String, vararg args: Any): StoredKey? = jdbc.query(
        """
            SELECT key_id, match_id, scope, created_at, revoked_at, secret_digest, derivation_version
            FROM obs_source_keys WHERE $predicate
        """.trimIndent(),
        { rs, _ -> StoredKey(metadata(rs), rs.getString("secret_digest"), rs.getObject("derivation_version", Int::class.javaObjectType)) },
        *args,
    ).singleOrNull()

    @Transactional(readOnly = true)
    fun list(): List<ObsSourceKeyMetadata> = jdbc.query(
        """
            SELECT key_id, match_id, scope, created_at, revoked_at
            FROM obs_source_keys ORDER BY created_at DESC, key_id
        """.trimIndent(),
    ) { rs, _ -> metadata(rs) }

    private fun metadata(rs: ResultSet) = ObsSourceKeyMetadata(
        rs.getObject("key_id", UUID::class.java),
        rs.getObject("match_id", UUID::class.java),
        ObsSourceKeyScope.valueOf(rs.getString("scope")),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("revoked_at")?.toInstant(),
    )

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

    private fun matchesDigest(expected: String, actual: String) =
        MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))

    private fun digest(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.US_ASCII)))

    private companion object {
        val bearer = Regex("Bearer +([A-Za-z0-9_-]{43})", RegexOption.IGNORE_CASE)
    }
}
