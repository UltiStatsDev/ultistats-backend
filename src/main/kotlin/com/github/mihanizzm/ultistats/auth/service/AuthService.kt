package com.github.mihanizzm.ultistats.auth.service

import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.repository.*
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class AuthService(private val store: AuthStore, private val encoder: PasswordEncoder, private val clock: Clock) {
    private val idle = Duration.ofDays(14)
    private val dummyHash = encoder.encode(AuthValidation.secret())
    fun installationId(): UUID = store.installationId()

    fun context(credential: String?): AuthContext {
        if (credential == null) return AuthContext(installationId(),clock.instant(),AuthState.ANONYMOUS,null,null,emptyList(),null,null,null)
        val session = credentialSession(credential)
        return context(active(session))
    }

    /** Never trusts cached request roles; used again inside the mutation authority fence. */
    fun principal(credential: String): AuthPrincipal {
        val session=credentialSession(credential)
        val context = context(active(session))
        return AuthPrincipal(context.installationId,context.account!!.id,context.sessionId!!,context.permissions.toSet(),
            context.state==AuthState.PASSWORD_CHANGE_REQUIRED,session.credentialGeneration)
    }

    fun login(login: String, password: String, previousCredential: String?): AuthLoginResult = audited(null,null,"LOGIN") {
        val normalized = AuthValidation.login(login)
        AuthValidation.password(password)
        val candidate = store.account(normalized)
        val matches = encoder.matches(password,candidate?.passwordHash ?: dummyHash)
        if (!matches || candidate == null) fail(401,"AUTH_CREDENTIALS_INVALID")
        store.withAuthorityLock {
            val account = store.account(normalized) ?: fail(401,"AUTH_CREDENTIALS_INVALID")
            if (account.passwordHash != candidate.passwordHash || account.view.status != AuthAccountStatus.ACTIVE)
                fail(401,"AUTH_CREDENTIALS_INVALID")
            val now = clock.instant()
            val previous = previousCredential?.let { store.sessionByDigest(digest(it)) }
            previous?.let { store.revoke(it.id,now) }
            val credential = AuthValidation.secret()
            val session = SessionRecord(UUID.randomUUID(),account.view.id,digest(credential),now,now,
                if (account.view.mustChangePassword) null else now.plusSeconds(300),null)
            store.insertSession(session)
            store.audit(account.view.id,account.view.id,now,"LOGIN","SUCCESS")
            AuthLoginResult(context(session to account,previous?.id),credential)
        }
    }

    fun renew(principal: AuthPrincipal): AuthContext = store.withAuthorityLock {
        val (session,_) = current(principal)
        store.renew(session.id,clock.instant())
        context(active(store.session(session.id)!!))
    }

    fun logout(credential: String, expectedSessionId: UUID): LogoutResult = store.withAuthorityLock {
        val session = credentialSession(credential)
        if (session.id != expectedSessionId) fail(403,"AUTH_IDENTITY_MISMATCH")
        val inactive = session.revokedAt != null || !clock.instant().isBefore(session.lastActivityAt.plus(idle))
        store.revoke(session.id,clock.instant())
        store.audit(session.accountId,session.accountId,clock.instant(),"LOGOUT",if(inactive) "ALREADY_INACTIVE" else "SUCCESS")
        LogoutResult(session.id,if(inactive) LogoutStatus.ALREADY_INACTIVE else LogoutStatus.REVOKED)
    }

    /** Identity proof for logout tombstones; does not grant protected permissions. */
    fun logoutPrincipal(credential: String): AuthPrincipal {
        val session = credentialSession(credential)
        return AuthPrincipal(installationId(),session.accountId,session.id,emptySet(),false,session.credentialGeneration)
    }

    fun changePassword(principal: AuthPrincipal,currentPassword: String,newPassword: String): AuthLoginResult =
        audited(principal.accountId,principal.accountId,"PASSWORD") {
            AuthValidation.password(currentPassword); AuthValidation.password(newPassword)
            val candidate = current(principal,true).second
            if (!encoder.matches(currentPassword,candidate.passwordHash)) fail(401,"AUTH_CREDENTIALS_INVALID")
            val hash = encoder.encode(newPassword) // expensive work before taking the management fence
            store.withAuthorityLock {
                val (session,account) = current(principal,true)
                if (account.passwordHash != candidate.passwordHash) fail(401,"AUTH_SESSION_INVALID")
                val now = clock.instant()
                store.saveAccount(account.view.copy(mustChangePassword=false,updatedAt=now),hash)
                store.revokeAccount(account.view.id,now,session.id)
                store.reauth(session.id,now.plusSeconds(300))
                val credential=AuthValidation.secret()
                store.rotate(session,digest(credential),now)
                AuthLoginResult(context(active(store.session(session.id)!!)),credential)
            }
        }

    fun reauthenticate(principal: AuthPrincipal,password: String): Instant = audited(principal.accountId,principal.accountId,"REAUTH") {
        AuthValidation.password(password)
        val candidate = current(principal).second
        if (!encoder.matches(password,candidate.passwordHash)) fail(401,"AUTH_CREDENTIALS_INVALID")
        store.withAuthorityLock {
            val (session,account) = current(principal)
            if (account.passwordHash != candidate.passwordHash) fail(401,"AUTH_SESSION_INVALID")
            clock.instant().plusSeconds(300).also { store.reauth(session.id,it) }
        }
    }

    fun listAccounts(page: Int,size: Int,search: String?): AccountPageResponse {
        require(page>=0 && size in 1..100) { "Invalid account pagination" }
        return store.listAccounts(page,size,AuthValidation.search(search))
    }

    fun createAccount(actor: AuthPrincipal,request: CreateAccountRequest): TemporaryAccountResult {
        currentAdmin(actor)
        val login = AuthValidation.login(request.login)
        val name = AuthValidation.displayName(request.displayName)
        val secret = AuthValidation.secret()
        val hash = encoder.encode(secret)
        return audited(actor.accountId,null,"ACCOUNT_CREATE") { store.withAuthorityLock {
            currentAdmin(actor)
            if (store.account(login)!=null) fail(409,"ACCOUNT_LOGIN_TAKEN")
            val now = clock.instant()
            val view = AccountView(UUID.randomUUID(),login,name,request.role,AuthAccountStatus.ACTIVE,true,now,now)
            store.saveAccount(view,hash,true)
            TemporaryAccountResult(view,secret)
        } }
    }

    fun updateAccount(actor: AuthPrincipal,id: UUID,request: UpdateAccountRequest): AccountView = audited(actor.accountId,id,"ACCOUNT_UPDATE") {
        val login = request.login?.let(AuthValidation::login)
        val name = request.displayName?.let(AuthValidation::displayName)
        store.withAuthorityLock {
            currentAdmin(actor)
            val old = store.account(id) ?: fail(404,"ACCOUNT_NOT_FOUND")
            val updated = old.view.copy(login=login ?: old.view.login,displayName=name ?: old.view.displayName,
                role=request.role ?: old.view.role,status=request.status ?: old.view.status,updatedAt=clock.instant())
            if (store.account(updated.login)?.view?.id?.let { it!=id } == true) fail(409,"ACCOUNT_LOGIN_TAKEN")
            if (old.view.role==AuthRole.ADMIN && old.view.status==AuthAccountStatus.ACTIVE &&
                (updated.role!=AuthRole.ADMIN || updated.status!=AuthAccountStatus.ACTIVE) && store.activeAdminCount()<=1)
                fail(409,"LAST_ADMIN_REQUIRED")
            store.saveAccount(updated,old.passwordHash)
            if(updated.role!=old.view.role || updated.status!=old.view.status) store.revokeAccount(id,clock.instant())
            updated
        }
    }

    fun resetPassword(actor: AuthPrincipal,id: UUID): TemporaryAccountResult {
        currentAdmin(actor)
        val secret=AuthValidation.secret()
        val hash=encoder.encode(secret)
        return audited(actor.accountId,id,"PASSWORD_RESET") { store.withAuthorityLock {
            currentAdmin(actor)
            val account=store.account(id) ?: fail(404,"ACCOUNT_NOT_FOUND")
            val updated=account.view.copy(mustChangePassword=true,updatedAt=clock.instant())
            store.saveAccount(updated,hash)
            store.revokeAccount(id,clock.instant())
            TemporaryAccountResult(updated,secret)
        } }
    }

    fun ownerBootstrap(login: String,displayName: String,password: String): AccountView {
        val normalized=AuthValidation.login(login)
        val name=AuthValidation.displayName(displayName)
        val hash=encoder.encode(AuthValidation.password(password))
        return audited(null,null,"OWNER_BOOTSTRAP") { store.withAuthorityLock {
            if(store.accountCount()!=0L) fail(409,"OWNER_ALREADY_INITIALIZED")
            val now=clock.instant()
            val view=AccountView(UUID.randomUUID(),normalized,name,AuthRole.ADMIN,AuthAccountStatus.ACTIVE,true,now,now)
            store.saveAccount(view,hash,true)
            view
        } }
    }

    fun ownerRecover(login: String,password: String): AccountView {
        val normalized=AuthValidation.login(login)
        val hash=encoder.encode(AuthValidation.password(password))
        return audited(null,null,"OWNER_RECOVER") { store.withAuthorityLock {
            val account=store.account(normalized) ?: fail(404,"ACCOUNT_NOT_FOUND")
            val updated=account.view.copy(role=AuthRole.ADMIN,status=AuthAccountStatus.ACTIVE,mustChangePassword=true,updatedAt=clock.instant())
            store.saveAccount(updated,hash)
            store.revokeAccount(updated.id,clock.instant())
            updated
        } }
    }

    private fun currentAdmin(actor: AuthPrincipal): Pair<SessionRecord,AccountRecord> {
        val pair=current(actor)
        if(pair.second.view.role!=AuthRole.ADMIN) fail(403,"AUTH_PERMISSION_DENIED")
        if(pair.first.reauthenticatedUntil?.let { clock.instant().isBefore(it) }!=true) fail(403,"AUTH_REAUTH_REQUIRED")
        return pair
    }
    private fun current(principal: AuthPrincipal,allowTemporary: Boolean=false): Pair<SessionRecord,AccountRecord> {
        if(principal.installationId!=installationId()) fail(403,"AUTH_IDENTITY_MISMATCH")
        val session=store.session(principal.sessionId) ?: fail(401,"AUTH_SESSION_INVALID")
        if(principal.credentialGeneration!=null && principal.credentialGeneration!=session.credentialGeneration) fail(401,"AUTH_SESSION_REVOKED")
        if(session.accountId!=principal.accountId) fail(403,"AUTH_IDENTITY_MISMATCH")
        val pair=active(session)
        if(!allowTemporary && pair.second.view.mustChangePassword) fail(403,"AUTH_PASSWORD_CHANGE_REQUIRED")
        return pair
    }
    private fun active(session: SessionRecord): Pair<SessionRecord,AccountRecord> {
        if(session.revokedAt!=null) fail(401,"AUTH_SESSION_REVOKED")
        if(!clock.instant().isBefore(session.lastActivityAt.plus(idle))) fail(401,"AUTH_SESSION_EXPIRED")
        val account=store.account(session.accountId) ?: fail(401,"AUTH_SESSION_INVALID")
        if(account.view.status!=AuthAccountStatus.ACTIVE) fail(403,"AUTH_ACCOUNT_BLOCKED")
        return session to account
    }
    private fun context(pair: Pair<SessionRecord,AccountRecord>,replaced: UUID?=null): AuthContext {
        val (session,account)=pair
        val permissions = if(account.view.mustChangePassword) emptyList() else AuthPermission.entries.filter {
            it!=AuthPermission.ACCOUNT_ADMIN || account.view.role==AuthRole.ADMIN
        }
        return AuthContext(installationId(),clock.instant(),if(account.view.mustChangePassword) AuthState.PASSWORD_CHANGE_REQUIRED else AuthState.AUTHENTICATED,
            session.id,account.view,permissions,session.lastActivityAt,session.lastActivityAt.plus(idle),session.reauthenticatedUntil,replaced)
    }
    private fun <T> audited(actor: UUID?,target: UUID?,action: String,block: ()->T): T {
        try {
            val result=block()
            val actualTarget = target ?: when(result) {
                is AccountView -> result.id
                is TemporaryAccountResult -> result.account.id
                is AuthContext -> result.account?.id
                else -> null
            }
            if(action!="LOGIN") store.audit(actor,actualTarget,clock.instant(),action,"SUCCESS")
            return result
        } catch(problem: AuthProblem) {
            store.audit(actor,target,clock.instant(),action,problem.code)
            throw problem
        }
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun credentialSession(credential:String):SessionRecord {
        val digest=digest(credential)
        return store.sessionByDigest(digest) ?: if(store.isRetiredCredential(digest)) fail(401,"AUTH_SESSION_REVOKED") else fail(401,"AUTH_SESSION_INVALID")
    }
    private fun fail(status: Int,code: String): Nothing = throw AuthProblem(status,code)
}
