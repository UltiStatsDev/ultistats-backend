package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class AccountAdministrationTest {
    @Test
    fun `create uses temporary secret rename preserves identity and duplicate normalized login is rejected`() {
        val f = AuthTestFixture()
        val actor = f.activeLogin().context.principal()
        val created = f.service.createAccount(actor, CreateAccountRequest(" Staff ", " Организатор ", AuthRole.ORGANIZER))
        assertEquals("staff", created.account.login)
        assertEquals("Организатор", created.account.displayName)
        assertTrue(created.account.mustChangePassword)
        assertTrue(created.temporaryPassword.length >= 8)
        val login = f.service.login("STAFF", created.temporaryPassword, null)
        assertTrue(login.context.permissions.isEmpty())
        val renamed = f.service.updateAccount(actor, created.account.id, UpdateAccountRequest(login = "Renamed"))
        assertEquals(created.account.id, renamed.id)
        assertEquals(created.account.id, f.service.context(login.credential).account!!.id)
        assertAuthProblem("ACCOUNT_LOGIN_TAKEN") {
            f.service.createAccount(actor, CreateAccountRequest("RENAMED", "Duplicate", AuthRole.ADMIN))
        }
    }

    @Test
    fun `step up expires at exact 300 second boundary and password confirmation does not advance idle`() {
        val f = AuthTestFixture()
        val login = f.activeLogin()
        val actor = login.context.principal()
        f.clock.now = f.clock.now.plusSeconds(300)
        assertAuthProblem("AUTH_REAUTH_REQUIRED") {
            f.service.createAccount(actor, CreateAccountRequest("staff", "Staff", AuthRole.ORGANIZER))
        }
        f.service.reauthenticate(actor, "permanent-password")
        val result = f.service.createAccount(actor, CreateAccountRequest("staff", "Staff", AuthRole.ORGANIZER))
        assertEquals("staff", result.account.login)
        assertEquals(login.context.lastActivityAt, f.service.context(login.credential).lastActivityAt)
    }

    @Test
    fun `temporary admin cannot administer even with fresh password confirmation`() {
        val f = AuthTestFixture()
        val login = f.login()
        assertAuthProblem("AUTH_PASSWORD_CHANGE_REQUIRED") { f.service.reauthenticate(login.context.principal(), "owner-password") }
        assertAuthProblem("AUTH_PASSWORD_CHANGE_REQUIRED") {
            f.service.createAccount(login.context.principal(), CreateAccountRequest("staff", "Staff", AuthRole.ORGANIZER))
        }
    }

    @Test
    fun `last active administrator cannot be blocked or demoted`() {
        val f = AuthTestFixture()
        val actor = f.activeLogin().context.principal()
        assertAuthProblem("LAST_ADMIN_REQUIRED") {
            f.service.updateAccount(actor, actor.accountId, UpdateAccountRequest(status = AuthAccountStatus.BLOCKED))
        }
        assertAuthProblem("LAST_ADMIN_REQUIRED") {
            f.service.updateAccount(actor, actor.accountId, UpdateAccountRequest(role = AuthRole.ORGANIZER))
        }
    }

    @Test
    fun `role block and password reset revoke all devices and unblock never resurrects`() {
        val f = AuthTestFixture()
        val actor = f.activeLogin().context.principal()
        val created = f.service.createAccount(actor, CreateAccountRequest("staff", "Staff", AuthRole.ORGANIZER))
        val first = f.service.login("staff", created.temporaryPassword, null)
        val second = f.service.login("staff", created.temporaryPassword, null)
        f.service.updateAccount(actor, created.account.id, UpdateAccountRequest(role = AuthRole.ADMIN))
        listOf(first, second).forEach { login -> assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(login.credential) } }
        val third = f.service.login("staff", created.temporaryPassword, null)
        f.service.updateAccount(actor, created.account.id, UpdateAccountRequest(status = AuthAccountStatus.BLOCKED))
        assertAuthProblem("AUTH_CREDENTIALS_INVALID") { f.service.login("staff", created.temporaryPassword, null) }
        f.service.updateAccount(actor, created.account.id, UpdateAccountRequest(status = AuthAccountStatus.ACTIVE))
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(third.credential) }
        val fourth = f.service.login("staff", created.temporaryPassword, null)
        val reset = f.service.resetPassword(actor, created.account.id)
        assertTrue(created.temporaryPassword != reset.temporaryPassword, "Reset must generate a new secret")
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(fourth.credential) }
        assertAuthProblem("AUTH_CREDENTIALS_INVALID") { f.service.login("staff", created.temporaryPassword, null) }
        assertTrue(f.service.login("staff", reset.temporaryPassword, null).context.account!!.mustChangePassword)
    }

    @Test
    fun `literal search does not treat SQL wildcards as account patterns and page order is stable`() {
        val f = AuthTestFixture()
        val actor = f.activeLogin().context.principal()
        f.service.createAccount(actor, CreateAccountRequest("zebra", "Name%_\\", AuthRole.ORGANIZER))
        f.service.createAccount(actor, CreateAccountRequest("alpha", "Alpha", AuthRole.ORGANIZER))
        assertEquals(listOf("zebra"), f.service.listAccounts(0,20,"%_\\").content.map { it.login })
        assertEquals(listOf("alpha", "owner"), f.service.listAccounts(0,2,null).content.map { it.login })
        val page = f.service.listAccounts(1,2," ")
        assertEquals(3, page.totalElements)
        assertEquals(2, page.totalPages)
        assertEquals(listOf("zebra"), page.content.map { it.login })
        assertFailsWith<IllegalArgumentException> { f.service.listAccounts(0,101,null) }
        assertFailsWith<IllegalArgumentException> { f.service.listAccounts(-1,20,null) }
        assertFailsWith<IllegalArgumentException> { f.service.listAccounts(0,20,"x".repeat(121)) }
    }
}
