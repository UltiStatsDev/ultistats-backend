package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.AuthService
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.realtime.MatchEventStreamService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.mockito.Mockito
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=[
    "app.auth.ingress.loopback-mode=true","app.auth.ingress.private-port=0",
    "app.auth.ingress.public-origin=http://localhost:21152","app.auth.ingress.secure-origin=http://localhost:21152"])
@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class AuthConcurrencyPostgresTest {
    companion object {
        val schema="auth152_"+UUID.randomUUID().toString().replace("-","")
        @JvmStatic @DynamicPropertySource fun properties(registry:DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("TEST_POSTGRES_URL") }
            registry.add("spring.datasource.username") { System.getenv("TEST_POSTGRES_USERNAME") }
            registry.add("spring.datasource.password") { System.getenv("TEST_POSTGRES_PASSWORD") }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.hikari.connection-init-sql") { "SET search_path TO $schema" }
            registry.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.PostgreSQLDialect" }
            registry.add("spring.jpa.properties.hibernate.default_schema") { schema }
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.sql.init.mode") { "never" }
        }
    }
    @Autowired lateinit var auth:AuthService
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var mapper:ObjectMapper
    @Autowired lateinit var teams:TeamService
    @Autowired lateinit var matches:MatchService
    @MockitoSpyBean lateinit var streams:MatchEventStreamService
    @LocalServerPort var port:Int=0
    private val password="Synthetic-owner-152"
    private lateinit var login:AuthLoginResult
    private lateinit var client:HttpClient
    private var token=""
    @BeforeEach fun owner() {
        if(auth.listAccounts(0,1,null).totalElements==0L) {
            auth.ownerBootstrap("owner152","Synthetic owner",password)
            val initial=auth.login("owner152",password,null)
            auth.changePassword(auth.principal(initial.credential),password,password)
        }
        login=auth.login("owner152",password,null)
        client=HttpClient.newBuilder().cookieHandler(CookieManager()).build()
        val manager=client.cookieHandler().get() as CookieManager
        manager.cookieStore.add(URI("http://127.0.0.1:$port"),java.net.HttpCookie("ULTISTATS_LOOPBACK_SESSION",login.credential).apply { path="/";domain="127.0.0.1";version=0 })
        val csrf=client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,csrf.statusCode())
        token=mapper.readTree(csrf.body())["token"].asText()
    }
    private fun uri(path:String)=URI("http://127.0.0.1:$port$path")
    private fun post(path:String,body:Any,origin:String?=null):HttpResponse<String> = client.send(HttpRequest.newBuilder(uri(path))
        .apply { origin?.let { header("Origin",it) } }
        .header("Content-Type","application/json").header("X-CSRF-TOKEN",token)
        .header("X-UltiStats-Installation-Id",login.context.installationId.toString())
        .header("X-UltiStats-Account-Id",login.context.account!!.id.toString())
        .header("X-UltiStats-Session-Id",login.context.sessionId.toString())
        .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString())
    private fun connection()=DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),System.getenv("TEST_POSTGRES_USERNAME"),System.getenv("TEST_POSTGRES_PASSWORD"))
    private fun waitFor(predicate:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!predicate()) { check(System.nanoTime()<end) { "Timed out waiting for owned PostgreSQL fence" };Thread.sleep(10) }
    }

    @Test fun `wrong origin on actual private HTTP returns frozen problem before CORS plaintext fallback`() {
        val response=post("/api/v1/teams",mapOf("name" to "must-not-exist"),"https://unapproved.invalid")
        assertEquals(403,response.statusCode())
        assertEquals("CSRF_INVALID",mapper.readTree(response.body())["code"]?.asText())
        assertTrue(mapper.readTree(response.body())["requestRejectedBeforeExecution"].asBoolean())
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty())
    }

    @Test fun `configured credential-free browser preflight remains allowed`() {
        val response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/teams"))
            .header("Origin","http://localhost:21152").header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","content-type,x-csrf-token,x-ultistats-installation-id,x-ultistats-account-id,x-ultistats-session-id")
            .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,response.statusCode())
        assertEquals("http://localhost:21152",response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty())
    }

    @Test fun `wrong captured identity with no CSRF cookie produces zero cookie refresh on actual HTTP`() {
        val raw=HttpClient.newHttpClient()
        val response=raw.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
            .header("Cookie","ULTISTATS_LOOPBACK_SESSION=${login.credential}")
            .header("X-UltiStats-Installation-Id",login.context.installationId.toString())
            .header("X-UltiStats-Account-Id",UUID.randomUUID().toString())
            .header("X-UltiStats-Session-Id",login.context.sessionId.toString()).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(403,response.statusCode())
        assertEquals("AUTH_IDENTITY_MISMATCH",mapper.readTree(response.body())["code"].asText())
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty())
    }

    @Test fun `password rotation preserves logical identity but old bearer cannot logout replacement`() {
        val response=post("/api/v1/auth/password",mapOf("currentPassword" to password,"newPassword" to password))
        assertEquals(200,response.statusCode())
        assertEquals(login.context.sessionId.toString(),mapper.readTree(response.body())["sessionId"].asText())
        assertAuthProblem("AUTH_SESSION_REVOKED") { auth.logout(login.credential,login.context.sessionId!!) }
        val session=client.send(HttpRequest.newBuilder(uri("/api/v1/auth/session")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,session.statusCode())
        assertEquals(login.context.sessionId.toString(),mapper.readTree(session.body())["sessionId"].asText())
    }

    @Test fun `concurrent administrators cannot both remove their last active admin role`() {
        val first=auth.principal(login.credential)
        val name="admin"+UUID.randomUUID().toString().replace("-","")
        val secondAccount=auth.createAccount(first,CreateAccountRequest(name,"Second admin",AuthRole.ADMIN))
        val temporary=auth.login(name,secondAccount.temporaryPassword,null)
        val permanent=auth.changePassword(auth.principal(temporary.credential),secondAccount.temporaryPassword,"Second-admin-152")
        val second=auth.principal(permanent.credential)
        // Remove any admins left by other tests except the pair under examination.
        jdbc.update("UPDATE auth_accounts SET role='ORGANIZER' WHERE id NOT IN (?,?)",first.accountId,second.accountId)
        val results=Executors.newFixedThreadPool(2).use { executor ->
            listOf(first,second).map { actor -> executor.submit<String> {
                try { auth.updateAccount(actor,actor.accountId,UpdateAccountRequest(role=AuthRole.ORGANIZER));"OK" }
                catch(problem:AuthProblem) { problem.code }
            } }.map { it.get(10,TimeUnit.SECONDS) }
        }
        assertEquals(listOf("LAST_ADMIN_REQUIRED","OK"),results.sorted())
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM auth_accounts WHERE role='ADMIN' AND status='ACTIVE'",Int::class.java))
        // Restore only synthetic owner for the next case, with the actual recovery operation.
        auth.ownerRecover("owner152",password)
        val initial=auth.login("owner152",password,null)
        auth.changePassword(auth.principal(initial.credential),password,password)
    }

    @Test fun `queued revoke wins before blocked writer and fresh authority denies effects`() {
        val name="blocked-"+UUID.randomUUID()
        connection().use { holder ->
            holder.autoCommit=false
            holder.createStatement().execute("SELECT pg_advisory_xact_lock(1598115923,152)")
            Executors.newFixedThreadPool(2).use { executor ->
                val revoke=executor.submit { auth.logout(login.credential,login.context.sessionId!!) }
                waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=1598115923 AND objid=152 AND NOT granted AND mode='ExclusiveLock'",Int::class.java)!!>0 }
                val writer=executor.submit<HttpResponse<String>> { post("/api/v1/teams",mapOf("name" to name)) }
                waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=1598115923 AND objid=152 AND NOT granted AND mode='ShareLock'",Int::class.java)!!>0 }
                holder.commit()
                revoke.get(10,TimeUnit.SECONDS)
                assertEquals(401,writer.get(10,TimeUnit.SECONDS).statusCode())
            }
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM teams WHERE name=?",Int::class.java,name))
    }

    @Test fun `terminating owned writer fence connection rolls back same protected transaction`() {
        val name="terminated-"+UUID.randomUUID()
        jdbc.execute("CREATE FUNCTION auth152_pause_team() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock(1598115923,153); RETURN NEW; END'")
        jdbc.execute("CREATE TRIGGER auth152_pause_team BEFORE INSERT ON teams FOR EACH ROW EXECUTE FUNCTION auth152_pause_team()")
        try {
            connection().use { holder ->
                holder.autoCommit=false
                holder.createStatement().execute("SELECT pg_advisory_xact_lock(1598115923,153)")
                Executors.newSingleThreadExecutor().use { executor ->
                    val writer=executor.submit<HttpResponse<String>> { post("/api/v1/teams",mapOf("name" to name)) }
                    try {
                    waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=1598115923 AND objid=152 AND granted AND mode='ShareLock'",Int::class.java)!!>0 }
                    val pid=jdbc.queryForObject("SELECT pid FROM pg_locks WHERE locktype='advisory' AND classid=1598115923 AND objid=152 AND granted AND mode='ShareLock'",Int::class.java)!!
                    assertTrue(jdbc.queryForObject("SELECT pg_terminate_backend(?)",Boolean::class.java,pid)!!)
                    auth.logout(login.credential,login.context.sessionId!!)
                    holder.commit()
                    assertTrue(writer.get(10,TimeUnit.SECONDS).statusCode()>=500)
                    } finally { if(!holder.isClosed) holder.rollback() }
                }
            }
        } finally { jdbc.execute("DROP TRIGGER auth152_pause_team ON teams");jdbc.execute("DROP FUNCTION auth152_pause_team()") }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM teams WHERE name=?",Int::class.java,name))
    }

    private fun startedMatch():Pair<UUID,UUID> {
        val sides=List(2) { Team(UUID.randomUUID(),"Auth152 side") }.onEach(teams::create)
        val match=Match(UUID.randomUUID(),sides.map { it.id })
        matches.create(match)
        matches.startMatch(match.id,Instant.parse("2026-11-01T00:00:00Z"))
        return match.id to sides.first().id
    }
    private fun batch(id:UUID,team:UUID)=mapOf("expectedLastSequenceNumber" to 0,"expectedLogRevision" to 0,
        "events" to listOf(mapOf("eventId" to id,"type" to "TIMEOUT_START","occurredAt" to "2026-11-01T00:00:01Z","teamId" to team)))

    @Test fun `global event UUID conflict rolls back before controller snapshot and emits once`() {
        val first=startedMatch();val second=startedMatch();val eventId=UUID.randomUUID()
        Mockito.clearInvocations(streams)
        jdbc.execute("CREATE FUNCTION auth152_uuid_gate() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock(1598115923,154); RETURN NEW; END'")
        jdbc.execute("CREATE TRIGGER auth152_uuid_gate BEFORE INSERT ON events FOR EACH ROW EXECUTE FUNCTION auth152_uuid_gate()")
        val results=try { connection().use { holder ->
            holder.autoCommit=false
            holder.createStatement().execute("SELECT pg_advisory_xact_lock(1598115923,154)")
            Executors.newFixedThreadPool(2).use { executor ->
                try {
                    val requests=listOf(first,second).map { match -> executor.submit<HttpResponse<String>> {
                        post("/api/v2/matches/${match.first}/events/batch",batch(eventId,match.second))
                    } }
                    waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND classid=1598115923 AND objid=154 AND NOT granted",Int::class.java)!!>=2 }
                    holder.commit()
                    requests.map { it.get(10,TimeUnit.SECONDS) }
                } finally { holder.rollback() }
            }
        } } finally { jdbc.execute("DROP TRIGGER auth152_uuid_gate ON events");jdbc.execute("DROP FUNCTION auth152_uuid_gate()") }
        assertEquals(listOf(201,409),results.map { it.statusCode() }.sorted())
        val loserIndex=results.indexOfFirst { it.statusCode()==409 }
        val loserMatch=listOf(first,second)[loserIndex].first
        val loser=results[loserIndex]
        assertEquals(409,loser.statusCode(),loser.body())
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT",mapper.readTree(loser.body())["code"].asText())
        assertEquals(0,mapper.readTree(loser.body())["lastSequenceNumber"].asInt())
        assertEquals(0,mapper.readTree(loser.body())["logRevision"].asInt())
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM events WHERE match_id=?",Int::class.java,loserMatch))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM event_batch_receipts WHERE match_id=?",Int::class.java,loserMatch))
        val signals=Mockito.mockingDetails(streams).invocations.filter { it.method.name=="eventLogChanged" }
        assertEquals(1,signals.size)
    }

    @Test fun `deferred commit failure sends no success and emits no after commit event`() {
        val match=startedMatch()
        jdbc.execute("CREATE FUNCTION auth152_commit_fail() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic commit failure''; END'")
        jdbc.execute("CREATE CONSTRAINT TRIGGER auth152_commit_fail AFTER INSERT ON events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth152_commit_fail()")
        Mockito.clearInvocations(streams)
        try {
            val response=post("/api/v2/matches/${match.first}/events/batch",batch(UUID.randomUUID(),match.second))
            assertTrue(response.statusCode()>=500)
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM events WHERE match_id=?",Int::class.java,match.first))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM event_batch_receipts WHERE match_id=?",Int::class.java,match.first))
            assertTrue(Mockito.mockingDetails(streams).invocations.none { it.method.name=="eventLogChanged" })
        } finally { jdbc.execute("DROP TRIGGER auth152_commit_fail ON events");jdbc.execute("DROP FUNCTION auth152_commit_fail()") }
    }
}
