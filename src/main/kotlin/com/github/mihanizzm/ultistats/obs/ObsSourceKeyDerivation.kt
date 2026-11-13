package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
internal class ObsSourceKeyDerivation(@Value("\${app.obs.source-key-file:}") private val keyFile: String) {
    fun load(installationId: UUID): Material {
        val bytes = try {
            if (keyFile.isBlank()) keyMaterialUnavailable()
            val path = Path.of(keyFile)
            if (!Files.isRegularFile(path)) keyMaterialUnavailable()
            Files.newInputStream(path).use { it.readNBytes(33) }
        } catch (_: IOException) {
            keyMaterialUnavailable()
        } catch (_: InvalidPathException) {
            keyMaterialUnavailable()
        } catch (_: SecurityException) {
            keyMaterialUnavailable()
        }
        try {
            if (bytes.size != 32) keyMaterialUnavailable()
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(bytes, algorithm)) }
            return Material(mac, installationId)
        } finally {
            bytes.fill(0)
        }
    }

    class Material(private val mac: Mac, private val installationId: UUID) {
        fun fingerprint(): String = HexFormat.of().formatHex(sign(
            "ultistats/obs/key-file", VERSION.toString(), installationId.toString(),
        ))

        fun secret(metadata: ObsSourceKeyMetadata, version: Int): String {
            if (version != VERSION) keyMaterialUnavailable()
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(
                "ultistats/obs/source-key", version.toString(), installationId.toString(),
                metadata.scope.kind, "EXACT", metadata.matchId.toString(), metadata.keyId.toString(),
            ))
        }

        // The domain, format version, enum labels and canonical UUIDs are ASCII without NUL.
        private fun sign(vararg fields: String): ByteArray =
            mac.doFinal(fields.joinToString("\u0000").toByteArray(Charsets.US_ASCII))
    }

    companion object {
        const val VERSION = 1
    }
}

internal fun keyMaterialUnavailable(): Nothing = throw AuthProblem(503, "OBS_SOURCE_KEY_MATERIAL_UNAVAILABLE")
