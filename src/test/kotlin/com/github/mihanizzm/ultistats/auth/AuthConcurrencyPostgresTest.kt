package com.github.mihanizzm.ultistats.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.AuthService
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.LocalFileStorageService
import com.github.mihanizzm.ultistats.service.FileStorageException
import com.github.mihanizzm.ultistats.model.Player
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
import java.nio.file.Files
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
        val photoRoot=Files.createTempDirectory("auth152-pg-photos-")
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
            registry.add("spring.datasource.hikari.data-source-properties.ApplicationName") { "auth152-test-$schema" }
            registry.add("app.storage.root") { photoRoot.toString() }
        }
    }
    @Autowired lateinit var auth:AuthService
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var mapper:ObjectMapper
    @Autowired lateinit var teams:TeamService
    @Autowired lateinit var matches:MatchService
    @Autowired lateinit var players:PlayerService
    @MockitoSpyBean lateinit var fileStorage:LocalFileStorageService
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
    private fun ownedPausedBackendSql(projection:String)="""
            SELECT $projection
            FROM pg_stat_activity a
            JOIN pg_locks authority ON authority.pid=a.pid
            JOIN pg_locks paused ON paused.pid=a.pid
            WHERE a.datname=current_database() AND a.usename=current_user AND a.application_name=?
              AND authority.database=a.datid AND authority.locktype='advisory' AND authority.classid=1598115923
              AND authority.objid=152 AND authority.objsubid=2 AND authority.granted AND authority.mode='ShareLock'
              AND paused.database=a.datid AND paused.locktype='advisory' AND paused.classid=1598115923
              AND paused.objid=? AND paused.objsubid=2 AND NOT paused.granted
        """.trimIndent()
    private fun waitForOwnedPausedBackend(waitKey:Int) = waitFor {
        jdbc.queryForObject(ownedPausedBackendSql("count(*)"),Int::class.java,"auth152-test-$schema",waitKey)==1
    }
    private fun terminateOwnedPausedBackend(waitKey:Int,scenario:String) {
        val expectedApplication="auth152-test-$schema"
        val receipt=jdbc.queryForObject(ownedPausedBackendSql(
            "a.pid,a.datname,a.application_name,a.backend_start,pg_terminate_backend(a.pid) AS terminated"),
            { rows,_ -> mapOf("scenario" to scenario,"pid" to rows.getInt("pid"),
            "database" to rows.getString("datname"),"applicationName" to rows.getString("application_name"),
            "backendStart" to rows.getTimestamp("backend_start").toInstant().toString(),"terminated" to rows.getBoolean("terminated")) },
            expectedApplication,waitKey)!!
        assertEquals(expectedApplication,receipt["applicationName"])
        assertEquals(jdbc.queryForObject("SELECT current_database()",String::class.java),receipt["database"])
        assertEquals(true,receipt["terminated"])
        val directory=java.nio.file.Path.of("build/contracts");Files.createDirectories(directory)
        Files.writeString(directory.resolve("auth152-owned-backend-$schema-$waitKey.json"),mapper.writeValueAsString(receipt))
    }
    private val oldPhoto=byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,1)
    private val newPhoto=byteArrayOf(0x89.toByte(),0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,2)
    private fun photoRequest(id:UUID,bytes:ByteArray?=null):HttpResponse<String> {
        val boundary="auth152-photo-boundary"
        val body=bytes?.let { ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").toByteArray()+it+("\r\n--$boundary--\r\n").toByteArray() }
        val builder=HttpRequest.newBuilder(uri("/api/v1/players/$id/photo"))
            .header("X-CSRF-TOKEN",token).header("X-UltiStats-Installation-Id",login.context.installationId.toString())
            .header("X-UltiStats-Account-Id",login.context.account!!.id.toString()).header("X-UltiStats-Session-Id",login.context.sessionId.toString())
        if(body==null) builder.DELETE() else builder.header("Content-Type","multipart/form-data; boundary=$boundary").PUT(HttpRequest.BodyPublishers.ofByteArray(body))
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
    }
    private fun photoFixture():Pair<UUID,String> {
        val player=Player(UUID.randomUUID(),"Auth152","Synthetic photo")
        players.create(player)
        val response=photoRequest(player.id,oldPhoto)
        assertEquals(200,response.statusCode())
        return player.id to mapper.readTree(response.body())["url"].asText()
    }
    private fun photoPath(url:String)=photoRoot.resolve(url.substringAfterLast('/'))
    private fun photoFiles()=Files.list(photoRoot).use { it.map { path -> path.fileName.toString() }.toList().toSet() }
    private fun assertOriginalPhoto(id:UUID,url:String,before:Set<String>) {
        assertEquals(url,players.get(id)!!.photoUrl)
        assertTrue(Files.exists(photoPath(url)),"Original photo must survive the failed transaction")
        assertContentEquals(oldPhoto,Files.readAllBytes(photoPath(url)))
        assertEquals(before,photoFiles(),"Failed replacement must clean only its staged upload")
    }
    @Test fun `photo replacement commit failure preserves original URL file and hash`() {
        val (id,url)=photoFixture();val before=photoFiles()
        jdbc.execute("CREATE FUNCTION auth152_photo_fail() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic photo commit failure''; END'")
        jdbc.execute("CREATE CONSTRAINT TRIGGER auth152_photo_fail AFTER UPDATE ON players DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth152_photo_fail()")
        try {
            assertTrue(photoRequest(id,newPhoto).statusCode()>=500)
            assertOriginalPhoto(id,url,before)
        } finally { jdbc.execute("DROP TRIGGER auth152_photo_fail ON players");jdbc.execute("DROP FUNCTION auth152_photo_fail()") }
    }
    @Test fun `photo deletion commit failure preserves original URL file and hash`() {
        val (id,url)=photoFixture();val before=photoFiles()
        jdbc.execute("CREATE FUNCTION auth152_photo_fail() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''synthetic photo commit failure''; END'")
        jdbc.execute("CREATE CONSTRAINT TRIGGER auth152_photo_fail AFTER UPDATE ON players DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth152_photo_fail()")
        try {
            assertTrue(photoRequest(id).statusCode()>=500)
            assertOriginalPhoto(id,url,before)
        } finally { jdbc.execute("DROP TRIGGER auth152_photo_fail ON players");jdbc.execute("DROP FUNCTION auth152_photo_fail()") }
    }
    @Test fun `photo successful commit retains new file and removes old only after commit`() {
        val (id,url)=photoFixture()
        val response=photoRequest(id,newPhoto)
        assertEquals(200,response.statusCode())
        val replacement=mapper.readTree(response.body())["url"].asText()
        assertEquals(replacement,players.get(id)!!.photoUrl)
        assertContentEquals(newPhoto,Files.readAllBytes(photoPath(replacement)))
        assertFalse(Files.exists(photoPath(url)))
        assertEquals(204,photoRequest(id).statusCode())
        assertNull(players.get(id)!!.photoUrl)
        assertFalse(Files.exists(photoPath(replacement)))
    }
    @Test fun `photo postcommit cleanup failure never deletes the new canonical upload`() {
        val (id,url)=photoFixture()
        Mockito.doThrow(FileStorageException("Synthetic old-file cleanup failure")).`when`(fileStorage).delete(url)
        try {
            val response=photoRequest(id,newPhoto)
            assertEquals(200,response.statusCode())
            val replacement=mapper.readTree(response.body())["url"].asText()
            assertEquals(replacement,players.get(id)!!.photoUrl)
            assertContentEquals(newPhoto,Files.readAllBytes(photoPath(replacement)))
            assertTrue(Files.exists(photoPath(url)),"Failed completion cleanup may retain the old file")
        } finally { Mockito.doCallRealMethod().`when`(fileStorage).delete(url);fileStorage.delete(url) }
    }
    @Test fun `photo writer backend loss before commit preserves original and cleans staged file`() {
        val (id,url)=photoFixture();val before=photoFiles()
        jdbc.execute("CREATE FUNCTION auth152_photo_pause() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_advisory_xact_lock(1598115923,155); RETURN NEW; END'")
        jdbc.execute("CREATE CONSTRAINT TRIGGER auth152_photo_pause AFTER UPDATE ON players DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION auth152_photo_pause()")
        try { connection().use { holder ->
            holder.autoCommit=false;holder.createStatement().execute("SELECT pg_advisory_xact_lock(1598115923,155)")
            Executors.newSingleThreadExecutor().use { executor ->
                val writer=executor.submit<HttpResponse<String>> { photoRequest(id,newPhoto) }
                try {
                    waitForOwnedPausedBackend(155)
                    terminateOwnedPausedBackend(155,"photo-before-commit")
                    auth.logout(login.credential,login.context.sessionId!!)
                    holder.rollback()
                    assertTrue(writer.get(10,TimeUnit.SECONDS).statusCode()>=500)
                } finally { holder.rollback() }
            }
        } } finally { jdbc.execute("DROP TRIGGER auth152_photo_pause ON players");jdbc.execute("DROP FUNCTION auth152_photo_pause()") }
        assertOriginalPhoto(id,url,before)
    }

    @Test fun `actual application mapper preserves every nullable auth context field`() {
        val response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/auth/session")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,response.statusCode())
        val body=mapper.readTree(response.body())
        assertEquals(setOf("contractVersion","installationId","serverNow","state","sessionId","account","permissions",
            "lastActivityAt","idleExpiresAt","reauthenticatedUntil","replacedSessionId"),body.fieldNames().asSequence().toSet())
        listOf("sessionId","account","lastActivityAt","idleExpiresAt","reauthenticatedUntil","replacedSessionId").forEach { assertTrue(body[it].isNull,it) }
    }

    @Test fun `untouched protected OpenAPI validates actual anonymous and authenticated auth contexts`() {
        val docs=client.send(HttpRequest.newBuilder(uri("/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(200,docs.statusCode())
        val document=mapper.readTree(docs.body())
        val required=document["components"]["schemas"]["AuthContext"]["required"].map { it.asText() }.toSet()
        assertEquals(setOf("contractVersion","installationId","serverNow","state","sessionId","account","permissions",
            "lastActivityAt","idleExpiresAt","reauthenticatedUntil","replacedSessionId"),required)
        val root=mapper.createObjectNode().put("\$schema","https://json-schema.org/draft/2020-12/schema").put("\$ref","#/components/schemas/AuthContext")
        root.set<com.fasterxml.jackson.databind.JsonNode>("components",document["components"])
        val schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
            .getSchema(root,com.networknt.schema.SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build())
        for(http in listOf(HttpClient.newHttpClient(),client)) {
            val response=http.send(HttpRequest.newBuilder(uri("/api/v1/auth/session")).GET().build(),HttpResponse.BodyHandlers.ofString())
            val body=mapper.readTree(response.body())
            assertEquals(emptySet(),schema.validate(body))
            val missing=body.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().apply { remove("sessionId") }
            assertTrue(schema.validate(missing).isNotEmpty())
        }
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
                waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND locktype='advisory' AND classid=1598115923 AND objid=152 AND NOT granted AND mode='ExclusiveLock'",Int::class.java)!!>0 }
                val writer=executor.submit<HttpResponse<String>> { post("/api/v1/teams",mapOf("name" to name)) }
                waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND locktype='advisory' AND classid=1598115923 AND objid=152 AND NOT granted AND mode='ShareLock'",Int::class.java)!!>0 }
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
                    waitForOwnedPausedBackend(153)
                    terminateOwnedPausedBackend(153,"sports-before-mutation")
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
                    waitFor { jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE database=(SELECT oid FROM pg_database WHERE datname=current_database()) AND locktype='advisory' AND classid=1598115923 AND objid=154 AND NOT granted",Int::class.java)!!>=2 }
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
