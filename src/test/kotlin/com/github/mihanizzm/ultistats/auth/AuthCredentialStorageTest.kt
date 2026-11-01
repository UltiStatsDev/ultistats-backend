package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.config.AuthCoreConfiguration
import com.github.mihanizzm.ultistats.auth.service.AuthAuditMaintenance
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64
import kotlin.test.*

class AuthCredentialStorageTest {
    @Test fun `production encoder salts Argon2id and verifies full 1024 code point input without truncation`() {
        val encoder=AuthCoreConfiguration().authPasswordEncoder()
        val password="😀".repeat(1023)+"a"
        val first=encoder.encode(password)
        val second=encoder.encode(password)
        assertNotEquals(first,second)
        assertTrue(first.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"))
        assertEquals(16,Base64.getDecoder().decode(first.split('$')[4]).size)
        assertEquals(32,Base64.getDecoder().decode(first.split('$')[5]).size)
        assertTrue(encoder.matches(password,first))
        assertFalse(encoder.matches("😀".repeat(1023)+"b",first))
    }
    @Test fun `audit maintenance removes only expired records using server time`() {
        val f=AuthTestFixture()
        f.store.audit(null,null,f.clock.now.minus(Duration.ofDays(31)),"SYNTHETIC","SUCCESS")
        f.store.audit(null,null,f.clock.now.minus(Duration.ofDays(29)),"SYNTHETIC","SUCCESS")
        AuthAuditMaintenance(f.store,f.clock,30).clean()
        assertEquals(1,f.jdbc.queryForObject("SELECT count(*) FROM auth_audit WHERE action='SYNTHETIC'",Int::class.java))
    }
}
