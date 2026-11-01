package com.github.mihanizzm.ultistats.auth.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.util.UUID

enum class AuthEntryMode { ANONYMOUS, SECURE, LOOPBACK }
enum class AuthPermission { SPORTS_WRITE, STATISTICS_READ_ALL, TRANSFER, OBS_ADMIN, API_DOCS, ACCOUNT_ADMIN }
enum class AuthRole { ORGANIZER, ADMIN }
enum class AuthAccountStatus { ACTIVE, BLOCKED }
enum class AuthState { ANONYMOUS, AUTHENTICATED, PASSWORD_CHANGE_REQUIRED }
enum class LogoutStatus { REVOKED, ALREADY_INACTIVE }
data class AuthPrincipal(val installationId: UUID, val accountId: UUID, val sessionId: UUID,
    val permissions: Set<AuthPermission>, val passwordChangeRequired: Boolean)
data class AccountView(val id: UUID, val login: String, val displayName: String, val role: AuthRole,
    val status: AuthAccountStatus, val mustChangePassword: Boolean, val createdAt: Instant, val updatedAt: Instant)
data class AuthContext(val installationId: UUID, val serverNow: Instant, val state: AuthState,
    val sessionId: UUID?, val account: AccountView?, val permissions: List<AuthPermission>,
    val lastActivityAt: Instant?, val idleExpiresAt: Instant?, val reauthenticatedUntil: Instant?,
    val replacedSessionId: UUID? = null, val contractVersion: String = "1.0.0")
class AuthLoginResult(val context: AuthContext, @get:JsonIgnore val credential: String) {
    override fun toString() = "AuthLoginResult(sessionId=${context.sessionId})"
}
data class LogoutResult(val sessionId: UUID, val status: LogoutStatus)
data class CreateAccountRequest(val login: String, val displayName: String, val role: AuthRole)
data class UpdateAccountRequest(val login: String? = null, val displayName: String? = null,
    val role: AuthRole? = null, val status: AuthAccountStatus? = null)
class TemporaryAccountResult(val account: AccountView, val temporaryPassword: String) {
    override fun toString() = "TemporaryAccountResult(accountId=${account.id})"
}
data class AccountPageResponse(val content: List<AccountView>, val number: Int, val size: Int,
    val totalElements: Long, val totalPages: Int, val numberOfElements: Int,
    val first: Boolean, val last: Boolean, val empty: Boolean)
