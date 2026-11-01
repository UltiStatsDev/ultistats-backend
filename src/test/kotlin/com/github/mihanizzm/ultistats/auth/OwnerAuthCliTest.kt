package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.cli.OwnerAuthCli
import org.junit.jupiter.api.Test
import kotlin.test.*

class OwnerAuthCliTest {
    @Test fun `CLI rejects password arguments and unknown options before opening application`() {
        listOf(listOf("owner","bootstrap","--login=owner","--password=secret"),
            listOf("owner","recover","--login=owner","--password-file=/tmp/secret"),
            listOf("owner","bootstrap","--login=owner","--stdin","--unexpected=value")).forEach {
            assertFailsWith<IllegalArgumentException> { OwnerAuthCli.parse(it) }
        }
    }
    @Test fun `recovery reads explicit input preserves account UUID revokes sessions and emits no secret`() {
        val f=AuthTestFixture()
        val login=f.login()
        val command=OwnerAuthCli.parse(listOf("owner","recover","--login=owner","--stdin"))
        val chars="recovered-password".toCharArray()
        val output=mutableListOf<String>()
        OwnerAuthCli(f.service).execute(command,{chars},output::add)
        assertTrue(chars.all { it=='\u0000' })
        assertFalse(output.joinToString().contains("recovered-password"))
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(login.credential) }
        val recovered=f.service.login("owner","recovered-password",null)
        assertEquals(login.context.account!!.id,recovered.context.account!!.id)
        assertTrue(recovered.context.account!!.mustChangePassword)
    }
    @Test fun `normal service restart cannot reset owner and bootstrap cannot create a second first owner`() {
        val f=AuthTestFixture()
        assertAuthProblem("OWNER_ALREADY_INITIALIZED") { f.service.ownerBootstrap("second","Second","another-password") }
        assertEquals(f.login().context.account!!.id,f.service().login("owner","owner-password",null).context.account!!.id)
    }
}
