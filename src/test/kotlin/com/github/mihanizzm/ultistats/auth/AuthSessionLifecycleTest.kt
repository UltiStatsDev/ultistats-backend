package com.github.mihanizzm.ultistats.auth

import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.repository.AuthStore
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.auth.service.AuthService
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

internal class AuthTestClock(var now: Instant = Instant.parse("2026-11-01T00:00:00Z")) : Clock() {
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

internal class AuthTestFixture {
    val dataSource = DriverManagerDataSource("jdbc:h2:mem:auth_${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
    val jdbc = JdbcTemplate(dataSource)
    val clock = AuthTestClock()
    val encoder = Argon2PasswordEncoder(16,32,1,19456,2)
    init { ResourceDatabasePopulator(ClassPathResource("db/migration/V11__accounts_and_sessions.sql")).execute(dataSource) }
    val store = AuthStore(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
    fun service() = AuthService(store, encoder, clock)
    val service = service()
    init { service.ownerBootstrap("owner", "Owner", "owner-password") }
    fun login() = service.login("owner", "owner-password", null)
    fun activeLogin(): AuthLoginResult {
        val initial = login()
        service.changePassword(initial.context.principal(), "owner-password", "permanent-password")
        return service.login("owner", "permanent-password", null)
    }
}

internal fun AuthContext.principal() = AuthPrincipal(installationId, account!!.id, sessionId!!,
    permissions.toSet(), state == AuthState.PASSWORD_CHANGE_REQUIRED)

internal fun assertAuthProblem(code: String, block: () -> Unit) {
    assertEquals(code, assertFailsWith<AuthProblem>(block = block).code)
}

class AuthSessionLifecycleTest {
    @Test
    fun `temporary login has stable account and session but no protected permissions`() {
        val f = AuthTestFixture()
        val login = f.login()
        assertEquals(AuthState.PASSWORD_CHANGE_REQUIRED, login.context.state)
        assertTrue(login.context.permissions.isEmpty())
        assertEquals(login.context.sessionId, f.service.context(login.credential).sessionId)
        assertEquals(login.context.account!!.id, f.service.context(login.credential).account!!.id)
    }

    @Test
    fun `database reconnect preserves installation and opaque session without raw bearer storage`() {
        val f = AuthTestFixture()
        val login = f.login()
        assertEquals(login.context.installationId, f.service().installationId())
        assertEquals(login.context.sessionId, f.service().context(login.credential).sessionId)
        val digest = f.jdbc.queryForObject("select credential_digest from auth_sessions", String::class.java)!!
        assertNotEquals(login.credential, digest)
        assertEquals(64, digest.length)
    }

    @Test
    fun `passive reads and reauth never extend inactivity and true expiry cannot renew`() {
        val f = AuthTestFixture()
        val login = f.activeLogin()
        val activity = login.context.lastActivityAt
        f.clock.now = f.clock.now.plusSeconds(14 * 86400L - 1)
        repeat(3) { assertEquals(activity, f.service.context(login.credential).lastActivityAt) }
        f.service.reauthenticate(login.context.principal(), "permanent-password")
        assertEquals(activity, f.service.context(login.credential).lastActivityAt)
        f.clock.now = f.clock.now.plusSeconds(1)
        assertAuthProblem("AUTH_SESSION_EXPIRED") { f.service.renew(login.context.principal()) }
        assertAuthProblem("AUTH_SESSION_EXPIRED") { f.service.context(login.credential) }
    }

    @Test
    fun `explicit renewal retains logical session and uses monotonic activity`() {
        val f = AuthTestFixture()
        val login = f.activeLogin()
        f.clock.now = f.clock.now.plusSeconds(3600)
        val renewed = f.service.renew(login.context.principal())
        assertEquals(login.context.sessionId, renewed.sessionId)
        assertEquals(Instant.parse("2026-11-01T01:00:00Z"), renewed.lastActivityAt)
        assertEquals(Instant.parse("2026-11-15T01:00:00Z"), renewed.idleExpiresAt)
        f.clock.now = f.clock.now.minusSeconds(5)
        assertEquals(renewed.lastActivityAt, f.service.renew(login.context.principal()).lastActivityAt)
    }

    @Test
    fun `logout intent cannot revoke a different current cookie and retains exact tombstone proof`() {
        val f = AuthTestFixture()
        val first = f.activeLogin()
        val second = f.service.login("owner", "permanent-password", null)
        assertAuthProblem("AUTH_IDENTITY_MISMATCH") { f.service.logout(second.credential, first.context.sessionId!!) }
        assertEquals(second.context.sessionId, f.service.context(second.credential).sessionId)
        assertEquals(LogoutStatus.REVOKED, f.service.logout(first.credential, first.context.sessionId!!).status)
        assertEquals(LogoutStatus.ALREADY_INACTIVE, f.service.logout(first.credential, first.context.sessionId!!).status)
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service().context(first.credential) }
        assertAuthProblem("AUTH_SESSION_INVALID") { f.service.logout("unknown", first.context.sessionId!!) }
    }

    @Test
    fun `login replaces only the recognized previous cookie session`() {
        val f = AuthTestFixture()
        val first = f.activeLogin()
        val other = f.service.login("owner", "permanent-password", null)
        val replacement = f.service.login("owner", "permanent-password", first.credential)
        assertEquals(first.context.sessionId, replacement.context.replacedSessionId)
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(first.credential) }
        assertEquals(other.context.sessionId, f.service.context(other.credential).sessionId)
        assertNotEquals(first.context.sessionId, replacement.context.sessionId)
    }

    @Test
    fun `own password clears temporary restriction retains current and revokes other devices`() {
        val f = AuthTestFixture()
        val first = f.login()
        val second = f.login()
        f.clock.now = f.clock.now.plusSeconds(120)
        val changed = f.service.changePassword(first.context.principal(), "owner-password", "new-password")
        assertEquals(first.context.sessionId, changed.sessionId)
        assertEquals(first.context.lastActivityAt, changed.lastActivityAt)
        assertEquals(AuthState.AUTHENTICATED, changed.state)
        assertTrue(AuthPermission.ACCOUNT_ADMIN in changed.permissions)
        assertEquals(Instant.parse("2026-11-01T00:07:00Z"), changed.reauthenticatedUntil)
        assertAuthProblem("AUTH_SESSION_REVOKED") { f.service.context(second.credential) }
        assertAuthProblem("AUTH_CREDENTIALS_INVALID") { f.service.login("owner", "owner-password", null) }
        assertEquals(changed.account!!.id, f.service.login("owner", "new-password", null).context.account!!.id)
    }
}
