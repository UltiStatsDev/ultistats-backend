package com.github.mihanizzm.ultistats.obs

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ObsInfographicsContractTest {
    @Test
    fun `old four scope HMAC bytes stay frozen and new kind is separate`() {
        val installation = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val match = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val key = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")) }
        val material = ObsSourceKeyDerivation.Material(mac, installation)
        fun secret(scope: ObsSourceKeyScope) = material.secret(
            ObsSourceKeyMetadata(key, if (scope.auto) null else match, scope, Instant.EPOCH, null), 1,
        )
        val golden = mapOf(
            ObsSourceKeyScope.EXACT_SCOREBOARD to "-vgrLhYor2Y6Npvk1hdfvSnepm3Ju0GGP0PiAGpEYuQ",
            ObsSourceKeyScope.AUTO_SCOREBOARD to "rXFAeFy9Q7SVCLzGnZt1STtMiu899hjW2_2jBS-Pjw0",
            ObsSourceKeyScope.EXACT_EVENT_LOG to "DEkSsKnnPZQTfqxstijIJKYvX40Z0Csm49D3EK9eKnk",
            ObsSourceKeyScope.AUTO_EVENT_LOG to "G3l1KL8YzaFm7XI_HqWbi-dt6mhWBSFBd2Rx-qUfekc",
        )
        golden.forEach { (scope, expected) -> assertEquals(expected, secret(scope), scope.name) }
        assertNotEquals(secret(ObsSourceKeyScope.EXACT_SCOREBOARD), secret(ObsSourceKeyScope.EXACT_INFOGRAPHICS))
        assertNotEquals(secret(ObsSourceKeyScope.AUTO_SCOREBOARD), secret(ObsSourceKeyScope.AUTO_INFOGRAPHICS))
    }
}
