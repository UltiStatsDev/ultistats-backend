package com.github.mihanizzm.ultistats.csvimport

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.AuthService
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.PlayerService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.*
import java.net.http.*
import java.util.UUID
import kotlin.test.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.config.additional-location=file:src/main/resources/application.yaml",
    "server.tomcat.max-swallow-size=12MB", "server.tomcat.max-http-form-post-size=12MB",
    "spring.servlet.multipart.max-file-size=11MB", "spring.servlet.multipart.max-request-size=12MB",
    "ultistats.csv-import.enabled=true", "app.auth.ingress.loopback-mode=true", "app.auth.ingress.private-port=0",
    "app.auth.ingress.public-origin=http://localhost:21152", "app.auth.ingress.secure-origin=http://localhost:21152"])
@ExtendWith(OutputCaptureExtension::class)
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class CsvImportPostgresTest {
    companion object {
        val schema = "csv250_" + UUID.randomUUID().toString().replace("-", "")
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { System.getenv("TEST_POSTGRES_URL") }
            r.add("spring.datasource.username") { System.getenv("TEST_POSTGRES_USERNAME") }
            r.add("spring.datasource.password") { System.getenv("TEST_POSTGRES_PASSWORD") }
            r.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            r.add("spring.datasource.hikari.connection-init-sql") { "SET search_path TO $schema" }
            r.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.PostgreSQLDialect" }
            r.add("spring.jpa.properties.hibernate.default_schema") { schema }
            r.add("spring.flyway.enabled") { true }
            r.add("spring.flyway.schemas") { schema }
            r.add("spring.flyway.default-schema") { schema }
            r.add("spring.sql.init.mode") { "never" }
        }
    }
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var environment: ConfigurableEnvironment
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var teams: TeamService
    @Autowired lateinit var players: PlayerService
    @LocalServerPort var port = 0
    private lateinit var login: AuthLoginResult
    private lateinit var client: HttpClient
    private var token = ""
    private val path = "/api/v1/imports/teams-players/csv"
    private val a = "Team Title,City,status\nFalcons,North,rejected\n"
    private val r = "Player First Name,Player Last Name,Team,Jersey Number,role\nAlex,Stone,Falcons,,coach\nAlex,Stone,Falcons,0,other\n"
    @BeforeEach fun setup() {
        if (auth.listAccounts(0, 1, null).totalElements == 0L) {
            auth.ownerBootstrap("csv-owner", "Synthetic owner", "Synthetic-password-250")
            val first = auth.login("csv-owner", "Synthetic-password-250", null)
            auth.changePassword(auth.principal(first.credential), "Synthetic-password-250", "Synthetic-password-250")
        }
        login = auth.login("csv-owner", "Synthetic-password-250", null)
        connect(login)
    }
    private fun uri(p: String) = URI("http://127.0.0.1:$port$p")
    private fun connect(session: AuthLoginResult?) {
        val cookies = CookieManager()
        session?.let { cookies.cookieStore.add(uri("/"), HttpCookie("ULTISTATS_LOOPBACK_SESSION", it.credential).apply {
            path = "/"; domain = "127.0.0.1"; version = 0
        }) }
        client = HttpClient.newBuilder().cookieHandler(cookies).build()
        val response = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        token = mapper.readTree(response.body())["token"].asText()
    }
    private fun send(parts: List<Pair<String, String>> = listOf("applications" to a, "rosters" to r),
        csrf: String? = token, identity: String? = login.context.installationId.toString()): HttpResponse<String> {
        val boundary = "synthetic250boundary"
        val body = parts.joinToString("") { (name, text) ->
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"synthetic.csv\"\r\nContent-Type: text/csv\r\n\r\n$text\r\n"
        } + "--$boundary--\r\n"
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "multipart/form-data; boundary=$boundary")
            .apply { csrf?.let { header("X-CSRF-TOKEN", it) }; identity?.let { header("X-UltiStats-Installation-Id", it) } }
            .header("X-UltiStats-Account-Id", login.context.account!!.id.toString())
            .header("X-UltiStats-Session-Id", login.context.sessionId.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun counts() = listOf("teams", "players", "team_players").map {
        jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)!!
    }
    @Test fun `genuine HTTP creates new sets on every call and CRUD reads linked values`() {
        val oldTeam = Team(UUID.randomUUID(), "Falcons", "Unchanged")
        val oldPlayer = Player(UUID.randomUUID(), "Alex", "Stone")
        teams.create(oldTeam); players.create(oldPlayer)
        val before = counts()
        val first = send()
        assertEquals(201, first.statusCode(), first.body())
        val result = mapper.readTree(first.body())
        assertEquals(1, result["counts"]["teams"].asInt())
        assertEquals(2, result["counts"]["players"].asInt())
        assertEquals(2, result["counts"]["memberships"].asInt())
        assertEquals(2, result["players"][0]["rosterRow"].asInt())
        assertTrue(result["memberships"][0]["number"].isNull)
        val teamId = result["teams"][0]["teamId"].asText()
        val get = client.send(HttpRequest.newBuilder(uri("/api/v1/teams/$teamId")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, get.statusCode())
        assertEquals("North", mapper.readTree(get.body())["city"].asText())
        result["players"].forEach {
            val read = client.send(HttpRequest.newBuilder(uri("/api/v1/players/${it["playerId"].asText()}")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, read.statusCode()); assertEquals("Alex", mapper.readTree(read.body())["firstName"].asText())
        }
        val secondPlayers = mapper.readTree(send().body())["players"].map { it["playerId"].asText() }
        assertEquals(2, secondPlayers.toSet().size)
        assertTrue(result["players"].none { it["playerId"].asText() in secondPlayers })
        val repeat = send()
        assertEquals(201, repeat.statusCode(), repeat.body())
        assertNotEquals(teamId, mapper.readTree(repeat.body())["teams"][0]["teamId"].asText())
        assertEquals(before.zip(listOf(3L, 6L, 6L)) { x, y -> x + y }, counts())
        assertEquals(oldTeam, teams.get(oldTeam.id)); assertEquals(oldPlayer, players.get(oldPlayer.id))
    }
    @Test fun `invalid multipart validation and security never mutate`() {
        val before = counts()
        listOf(listOf("applications" to a), listOf("applications" to a, "rosters" to r, "rosters" to r),
            listOf("applications" to a, "rosters" to r, "unknown" to "private-value"),
            listOf("applications" to a, "rosters" to r.replace("Falcons,", "unknown,"))).forEach {
            val response = send(it); assertEquals(400, response.statusCode(), response.body())
            assertFalse(response.body().contains("private-value")); assertTrue(mapper.readTree(response.body()).has("errors"))
            if (it.size != 2) {
                val error = mapper.readTree(response.body())["errors"][0]
                assertTrue(error["row"].isNull); assertTrue(error["column"].isNull)
            }
        }
        assertEquals(413, send(listOf("applications" to a, "rosters" to "x".repeat(1048577))).statusCode())
        assertEquals(413, send(listOf("applications" to a, "rosters" to "x".repeat(11 * 1048576 + 1))).statusCode())
        assertEquals(403, send(csrf = null).statusCode()); assertEquals(403, send(csrf = "wrong").statusCode())
        assertEquals(403, send(identity = null).statusCode()); assertEquals(403, send(identity = UUID.randomUUID().toString()).statusCode())
        connect(null); assertEquals(401, send().statusCode())
        val temporary = auth.createAccount(auth.principal(login.credential), CreateAccountRequest("pending250", "Synthetic pending", AuthRole.ORGANIZER))
        login = auth.login("pending250", temporary.temporaryPassword, null); connect(login)
        assertEquals(403, send().statusCode())
        assertEquals(before, counts())
    }
    @Test fun `OpenAPI declares exact multipart and all result statuses`() {
        val response = client.send(HttpRequest.newBuilder(uri("/v3/api-docs"))
            .header("X-UltiStats-Installation-Id", login.context.installationId.toString())
            .header("X-UltiStats-Account-Id", login.context.account!!.id.toString())
            .header("X-UltiStats-Session-Id", login.context.sessionId.toString()).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        val spec = mapper.readTree(response.body())
        val operation = spec["paths"][path]["post"]
        val upload = spec["components"]["schemas"]["CsvImportUpload"]
        assertEquals(setOf("applications", "rosters"), upload["required"].map { it.asText() }.toSet())
        assertEquals("binary", upload["properties"]["applications"]["format"].asText())
        assertTrue(operation["requestBody"]["content"].has("multipart/form-data"))
        listOf("201", "400", "413", "404").forEach { assertTrue(operation["responses"].has(it)) }
        assertTrue(operation["responses"]["400"]["content"].has("application/problem+json"))
    }
    @Test fun `flag off is 404 and header-only rosters creates teams only`() {
        val before = counts()
        environment.propertySources.addFirst(MapPropertySource("csv250-disabled", mapOf("ultistats.csv-import.enabled" to false)))
        try { assertEquals(404, send().statusCode()); assertEquals(before, counts()) }
        finally { environment.propertySources.remove("csv250-disabled") }
        val response = send(listOf("applications" to a + "Falcons,South,accepted\n", "rosters" to r.substringBefore('\n') + "\n"))
        assertEquals(201, response.statusCode(), response.body())
        val result = mapper.readTree(response.body())
        assertEquals(2, result["counts"]["teams"].asInt()); assertEquals(0, result["counts"]["players"].asInt())
        assertEquals(before.zip(listOf(2L, 0L, 0L)) { x, y -> x + y }, counts())
    }
    @Test fun `late PostgreSQL constraint failure rolls back preceding inserts`(output: CapturedOutput) {
        val before = counts()
        jdbc.execute("ALTER TABLE players ADD CONSTRAINT csv250_late_failure CHECK (first_name <> 'SyntheticLateFailure')")
        try {
            val response = send(listOf("applications" to a, "rosters" to r + "SyntheticLateFailure,Stone,Falcons,1,player\n"))
            assertEquals(500, response.statusCode())
            assertFalse(response.body().contains("SyntheticLateFailure"))
            assertFalse(output.all.contains("SyntheticLateFailure"), "Persistence logs must omit uploaded values")
            assertEquals(before, counts())
        } finally { jdbc.execute("ALTER TABLE players DROP CONSTRAINT csv250_late_failure") }
    }
}
