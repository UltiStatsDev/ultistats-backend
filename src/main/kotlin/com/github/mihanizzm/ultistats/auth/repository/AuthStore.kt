package com.github.mihanizzm.ultistats.auth.repository

import com.github.mihanizzm.ultistats.auth.model.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal class AccountRecord(val view: AccountView, val passwordHash: String)
internal class SessionRecord(val id: UUID, val accountId: UUID, val credentialDigest: String,
    val createdAt: Instant, val lastActivityAt: Instant, val reauthenticatedUntil: Instant?, val revokedAt: Instant?,
    val credentialGeneration: UUID = UUID.randomUUID())

@Repository
class AuthStore(private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate) {
    private val accountMapper = RowMapper { rs: ResultSet, _: Int ->
        AccountRecord(AccountView(rs.uuid("id"), rs.getString("login"), rs.getString("display_name"),
            AuthRole.valueOf(rs.getString("role")), AuthAccountStatus.valueOf(rs.getString("status")),
            rs.getBoolean("must_change_password"), rs.instant("created_at")!!, rs.instant("updated_at")!!), rs.getString("password_hash"))
    }
    private val sessionMapper = RowMapper { rs: ResultSet, _: Int ->
        SessionRecord(rs.uuid("id"), rs.uuid("account_id"), rs.getString("credential_digest"),
            rs.instant("created_at")!!, rs.instant("last_activity_at")!!, rs.instant("reauthenticated_until"), rs.instant("revoked_at"),rs.uuid("credential_generation"))
    }

    fun installationId(): UUID = jdbc.queryForObject("SELECT installation_id FROM auth_installation WHERE singleton=1", UUID::class.java)!!

    /** Management always takes exclusive mode first; callers must never upgrade a shared fence. */
    fun <T : Any> withAuthorityLock(block: () -> T): T = fenced(false, block)
    fun <T : Any> withDomainAuthorityLock(block: () -> T): T = fenced(true, block)
    private fun <T : Any> fenced(shared: Boolean, block: () -> T): T = transactions.execute {
        val postgres = jdbc.dataSource!!.connection.use { it.metaData.databaseProductName == "PostgreSQL" }
        val suffix = if (shared && postgres) "FOR SHARE" else "FOR UPDATE"
        jdbc.queryForObject("SELECT singleton FROM auth_installation WHERE singleton=1 $suffix", Int::class.java)
        block()
    }!!

    internal fun account(id: UUID): AccountRecord? = jdbc.query("SELECT * FROM auth_accounts WHERE id=?", accountMapper, id).firstOrNull()
    internal fun account(login: String): AccountRecord? = jdbc.query("SELECT * FROM auth_accounts WHERE login=?", accountMapper, login).firstOrNull()
    internal fun accountCount(): Long = jdbc.queryForObject("SELECT count(*) FROM auth_accounts", Long::class.java)!!
    internal fun activeAdminCount(): Long = jdbc.queryForObject("SELECT count(*) FROM auth_accounts WHERE role='ADMIN' AND status='ACTIVE'", Long::class.java)!!
    internal fun saveAccount(view: AccountView, hash: String, insert: Boolean = false) {
        if (insert) jdbc.update("""INSERT INTO auth_accounts(id,login,display_name,password_hash,role,status,must_change_password,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?)""", view.id,view.login,view.displayName,hash,view.role.name,view.status.name,
            view.mustChangePassword,Timestamp.from(view.createdAt),Timestamp.from(view.updatedAt))
        else jdbc.update("""UPDATE auth_accounts SET login=?,display_name=?,password_hash=?,role=?,status=?,must_change_password=?,updated_at=? WHERE id=?""",
            view.login,view.displayName,hash,view.role.name,view.status.name,view.mustChangePassword,Timestamp.from(view.updatedAt),view.id)
    }
    internal fun sessionByDigest(digest: String): SessionRecord? = jdbc.query("SELECT * FROM auth_sessions WHERE credential_digest=?", sessionMapper,digest).firstOrNull()
    internal fun session(id: UUID): SessionRecord? = jdbc.query("SELECT * FROM auth_sessions WHERE id=?",sessionMapper,id).firstOrNull()
    internal fun isRetiredCredential(digest:String):Boolean = jdbc.queryForObject(
        "SELECT count(*) FROM auth_revoked_credentials WHERE credential_digest=?",Long::class.java,digest)!!>0
    internal fun rotate(session:SessionRecord,digest:String,now:Instant) {
        jdbc.update("INSERT INTO auth_revoked_credentials(credential_digest,session_id,account_id,revoked_at) VALUES (?,?,?,?)",
            session.credentialDigest,session.id,session.accountId,Timestamp.from(now))
        jdbc.update("UPDATE auth_sessions SET credential_digest=?,credential_generation=? WHERE id=?",digest,UUID.randomUUID(),session.id)
    }
    internal fun insertSession(session: SessionRecord) {
        jdbc.update("""INSERT INTO auth_sessions(id,account_id,credential_digest,created_at,last_activity_at,reauthenticated_until,revoked_at,credential_generation)
            VALUES (?,?,?,?,?,?,?,?)""",session.id,session.accountId,session.credentialDigest,Timestamp.from(session.createdAt),
            Timestamp.from(session.lastActivityAt),session.reauthenticatedUntil?.let(Timestamp::from),session.revokedAt?.let(Timestamp::from),session.credentialGeneration)
    }
    internal fun renew(id: UUID, now: Instant) {
        jdbc.update("UPDATE auth_sessions SET last_activity_at=CASE WHEN last_activity_at < ? THEN ? ELSE last_activity_at END WHERE id=?",
            Timestamp.from(now),Timestamp.from(now),id)
    }
    internal fun reauth(id: UUID, until: Instant) { jdbc.update("UPDATE auth_sessions SET reauthenticated_until=? WHERE id=?",Timestamp.from(until),id) }
    internal fun revoke(id: UUID, now: Instant) { jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE id=?",Timestamp.from(now),id) }
    internal fun revokeAccount(id: UUID, now: Instant, except: UUID? = null) {
        if (except == null) jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE account_id=?",Timestamp.from(now),id)
        else jdbc.update("UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,?) WHERE account_id=? AND id<>?",Timestamp.from(now),id,except)
    }
    internal fun listAccounts(page: Int, size: Int, search: String?): AccountPageResponse {
        val filter = if (search == null) "" else " WHERE lower(login) LIKE ? ESCAPE '\\' OR lower(display_name) LIKE ? ESCAPE '\\'"
        val escaped = search?.lowercase(java.util.Locale.ROOT)?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")
        val args = if (escaped == null) emptyArray<Any>() else arrayOf<Any>("%$escaped%", "%$escaped%")
        val total = jdbc.queryForObject("SELECT count(*) FROM auth_accounts$filter",Long::class.java,*args)!!
        val content = jdbc.query("SELECT * FROM auth_accounts$filter ORDER BY login ASC,id ASC LIMIT ? OFFSET ?",accountMapper,
            *args,size,page.toLong()*size).map { it.view }
        val pages = ((total+size-1)/size).toInt()
        return AccountPageResponse(content,page,size,total,pages,content.size,page==0,page.toLong()+1>=pages,content.isEmpty())
    }
    fun audit(actor: UUID?, target: UUID?, now: Instant, action: String, result: String) {
        jdbc.update("INSERT INTO auth_audit(id,actor_id,target_id,occurred_at,action,result) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(),actor,target,Timestamp.from(now),action,result)
    }
    fun pruneAudit(before: Instant, limit: Int = 1000): Int = jdbc.update(
        "DELETE FROM auth_audit WHERE id IN (SELECT id FROM auth_audit WHERE occurred_at < ? ORDER BY occurred_at LIMIT ?)",Timestamp.from(before),limit)
    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)
    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
