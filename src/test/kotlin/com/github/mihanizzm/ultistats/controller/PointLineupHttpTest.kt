package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.UltistatsApplication
import com.github.mihanizzm.ultistats.auth.model.AuthLoginResult
import com.github.mihanizzm.ultistats.auth.service.AuthService
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.auth.ingress.loopback-mode=true", "ultistats.point-lineups.write-enabled=true"],
)
@ActiveProfiles("point-lineup-h2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PointLineupHttpTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var matches: MatchService
    @Autowired private lateinit var teams: TeamService
    @Autowired private lateinit var players: PlayerService
    @Autowired private lateinit var memberships: TeamPlayerService
    private val client = HttpClient.newHttpClient()
    private val startedAt = Instant.parse("2026-10-15T09:00:00Z")
    private val login by lazy {
        auth.ownerBootstrap("lineup-owner", "Lineup owner", "initial-fixture-password")
        val first = auth.login("lineup-owner", "initial-fixture-password", null)
        auth.changePassword(auth.principal(first.credential), "initial-fixture-password", "changed-fixture-password")
    }

    @Test
    fun `public read and authorized confirmation preserve stable point and roster after PULL`() {
        val fixture = fixture()
        val roster = get(fixture.matchPath)
        val beforeResponse = send("GET", fixture.path)
        val before = response(beforeResponse, 200)
        assertTrue(before["writesEnabled"].asBoolean())
        assertEquals(0, before["logRevision"].asInt())
        val next = before["points"].single()
        assertEquals("NEXT", next["status"].asText())
        assertTrue(next["pullEventId"].isNull && next["closingEventId"].isNull && next["lineup"].isNull)
        val pointPath = "${fixture.path}/${next["pointId"].asText()}"
        val request = fixture.selection(left = listOf(player(fixture.leftPlayers[0]), temporary()), right = emptyList())

        response(send("PUT", pointPath, request), 401)
        assertEquals("CSRF_INVALID", response(send("PUT", pointPath, request, login, csrf = false), 403)["code"].asText())
        val confirmed = send("PUT", pointPath, request, login)
        val saved = response(confirmed, 200)
        assertEquals(1, saved["lineup"]["revision"].asInt())
        assertEquals(fixture.teamIds.map(UUID::toString), saved["lineup"]["teams"].map { it["teamId"].asText() })
        assertEquals(listOf("PLAYER", "TEMPORARY"), saved["lineup"]["teams"][0]["entries"].map { it["kind"].asText() })
        assertTrue(saved["lineup"]["teams"][0]["entries"][1]["resolvedParticipantId"].isNull)
        assertTrue(saved["lineup"]["teams"][1]["entries"].isEmpty)
        assertEquals(saved, get(fixture.path)["points"].single())

        val pullId = event(fixture, "PULL", fixture.rightPlayers[0])
        val afterResponse = send("GET", fixture.path)
        val after = response(afterResponse, 200)
        val active = after["points"].single()
        assertEquals(1, after["logRevision"].asInt())
        assertEquals(next["pointId"], active["pointId"])
        assertEquals("ACTIVE", active["status"].asText())
        assertEquals(pullId.toString(), active["pullEventId"].asText())
        assertEquals(saved["lineup"], active["lineup"])
        assertEquals(active, response(send("PUT", pointPath, request, login), 200))
        assertEquals(after, get(fixture.path))
        assertEquals(roster["teams"], get(fixture.matchPath)["teams"])
        assertEquals("POINT_LINEUP_EXPORT_UNSUPPORTED", response(send("POST", "/api/v1/matches/export",
            mapOf("matchIds" to listOf(fixture.id)), login), 409)["code"].asText())

        val rawSchema = send("GET", "/v3/api-docs", session = login)
        val api = response(rawSchema, 200)
        val artifactDir = System.getenv("POINT_LINEUP_ARTIFACT_DIR")?.let { Files.createDirectories(Path.of(it)) }
        artifactDir?.let { Files.writeString(it.resolve("openapi.json"), rawSchema.body()) }
        val getResponseSchema = api["paths"]["/api/v1/matches/{matchId}/point-lineups"]["get"]["responses"]
            .path("200").path("content").path("application/json").path("schema")
        assertEquals("#/components/schemas/PointLineupsResponse", getResponseSchema.path("\$ref").asText())
        val schemas = api["components"]["schemas"]
        val pointSchema = schemas["PointLineupResponse"]
        val temporarySchema = schemas["PointLineupTemporary"]
        assertTrue(pointSchema["required"].any { it.asText() == "lineup" })
        assertTrue(pointSchema["properties"]["lineup"]["anyOf"].any { it["type"]?.asText() == "null" })
        assertTrue(temporarySchema["required"].any { it.asText() == "resolvedParticipantId" })
        assertTrue(temporarySchema.path("properties").path("resolvedParticipantId").path("type").any { it.asText() == "null" })
        for (prefix in listOf("PointLineup", "ConfirmPointLineup")) {
            val schema = schemas["${prefix}Entry"]
            assertEquals(2, schema["oneOf"].size())
            assertEquals("kind", schema["discriminator"]["propertyName"].asText())
            for ((suffix, kind) in listOf("Player" to "PLAYER", "Temporary" to "TEMPORARY")) {
                val leaf = schemas["$prefix$suffix"]
                assertFalse(leaf.has("allOf"))
                assertEquals(kind, leaf["properties"]["kind"]["enum"].single().asText())
            }
        }

        artifactDir?.let { dir ->
            Files.writeString(dir.resolve("public-get-next.json"), beforeResponse.body())
            Files.writeString(dir.resolve("authorized-put-request.json"), mapper.writeValueAsString(request))
            Files.writeString(dir.resolve("authorized-put-response.json"), confirmed.body())
            Files.writeString(dir.resolve("public-get-after-pull.json"), afterResponse.body())
            val authorizedRead = send("GET", fixture.path, session = login)
            assertEquals(after, response(authorizedRead, 200))
            Files.writeString(dir.resolve("authorized-get-after-pull.json"), authorizedRead.body())
            Files.writeString(dir.resolve("paths.txt"), "${fixture.path}\n$pointPath\n")
        }
    }

    @Test
    fun `validates both snapshot teams and preserves incomplete and all temporary selections`() {
        val fixture = fixture()
        val point = get(fixture.path)["points"].single()["pointId"].asText()
        val path = "${fixture.path}/$point"
        val unknown = matches.getOrThrow(fixture.id).participantsByTeam.getValue(fixture.teamIds[0])
            .first { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        val slot = temporary()
        for (invalid in listOf(
            fixture.selection(left = listOf(player(UUID.randomUUID()))),
            fixture.selection(left = listOf(player(unknown))),
            fixture.selection(left = listOf(player(fixture.rightPlayers[0]))),
            fixture.selection(left = List(2) { player(fixture.leftPlayers[0]) }),
            fixture.selection(left = listOf(temporary(unknown))),
            fixture.selection(left = listOf(slot), right = listOf(slot)),
            fixture.selection(left = listOf(slot, temporary())),
            fixture.selection(left = listOf(temporary(number = 0))),
            fixture.selection(left = listOf(temporary(number = 3))),
            fixture.selection(left = listOf(temporary(), temporary(number = 2), player(fixture.leftPlayers[0]))),
            fixture.selection() + ("teams" to emptyList<Any>()),
            fixture.selection() + ("teams" to listOf(null, null)),
            fixture.selection(left = listOf(mapOf("kind" to "TEMPORARY", "slotId" to UUID.randomUUID(), "number" to 1.5))),
            fixture.selection(left = listOf(mapOf("kind" to "TEMPORARY", "slotId" to UUID.randomUUID(), "number" to "1"))),
            fixture.selection() + ("expectedLogRevision" to 0.5),
            fixture.selection() + ("expectedLogRevision" to "0"),
            fixture.selection() + ("expectedLogRevision" to null),
        )) {
            response(send("PUT", path, invalid, login), 400)
        }
        assertEquals("EVENT_LOG_STALE", response(send("PUT", path, fixture.selection(revision = 1), login), 409)["code"].asText())
        assertTrue(get(fixture.path)["points"].single()["lineup"].isNull)
        val request = fixture.selection(left = listOf(slot, temporary(number = 2)),
            right = listOf(temporary(), temporary(number = 2)))
        val saved = response(send("PUT", path, request, login), 200)
        @Suppress("UNCHECKED_CAST")
        val reversed = request + ("teams" to (request.getValue("teams") as List<Any>).reversed())
        assertEquals(saved, response(send("PUT", path, reversed, login), 200))
        assertEquals("POINT_LINEUP_CONFLICT", response(send("PUT", path, fixture.selection(), login), 409)["code"].asText())
        assertEquals(0, get(fixture.path)["logRevision"].asInt())
    }

    @Test
    fun `accepts complete roster-only lineups in request order`() {
        val fixture = fixture()
        val point = get(fixture.path)["points"].single()["pointId"].asText()
        val left = fixture.leftPlayers.reversed()
        val body = fixture.selection(left = left.map(::player), right = fixture.rightPlayers.map(::player))
        val saved = response(send("PUT", "${fixture.path}/$point", body, login), 200)
        val teams = saved["lineup"]["teams"]
        assertEquals(left, teams[0]["entries"].map { UUID.fromString(it["participantId"].asText()) })
        assertEquals(fixture.rightPlayers, teams[1]["entries"].map { UUID.fromString(it["participantId"].asText()) })
        assertEquals(saved, get(fixture.path)["points"].single())
    }

    @Test
    fun `undo removes a successor lineup from canonical read and reserves its slot on replacement`() {
        val fixture = fixture()
        val first = get(fixture.path)["points"].single()["pointId"]
        event(fixture, "PULL", fixture.rightPlayers[0])
        event(fixture, "PICKUP", fixture.leftPlayers[0])
        val goal = event(fixture, "GOAL", fixture.leftPlayers[0], fixture.leftPlayers[1])
        val scored = get(fixture.path)
        val completed = scored["points"][0]
        val next = scored["points"][1]
        assertEquals(first, completed["pointId"])
        assertEquals("COMPLETED", completed["status"].asText())
        assertEquals(goal.toString(), completed["closingEventId"].asText())
        assertTrue(completed["lineup"].isNull)
        assertEquals(2, next["number"].asInt())
        val removed = next["pointId"].asText()
        val slot = temporary()
        val accepted = fixture.selection(scored["logRevision"].asLong(), left = listOf(slot))
        response(send("PUT", "${fixture.path}/$removed", accepted, login), 200)
        response(send("POST", "${fixture.matchPath}/events/undo", session = login), 200)
        val undone = get(fixture.path)["points"].single()
        assertEquals(first, undone["pointId"])
        assertEquals("ACTIVE", undone["status"].asText())
        response(send("PUT", "${fixture.path}/$removed", accepted, login), 409)
        val callahan = event(fixture, "CALLAHAN", fixture.leftPlayers[0], fixture.rightPlayers[0])
        val replaced = get(fixture.path)
        assertEquals(callahan.toString(), replaced["points"][0]["closingEventId"].asText())
        val replacement = replaced["points"][1]
        assertNotEquals(removed, replacement["pointId"].asText())
        assertEquals(next["number"], replacement["number"])
        assertTrue(replacement["lineup"].isNull)
        response(send("PUT", "${fixture.path}/${replacement["pointId"].asText()}",
            fixture.selection(replaced["logRevision"].asLong(), left = listOf(slot)), login), 400)
        assertEquals("POINT_LINEUP_EXPORT_UNSUPPORTED", response(send("POST", "/api/v1/matches/export",
            mapOf("matchIds" to listOf(fixture.id)), login), 409)["code"].asText())
        response(send("POST", "${fixture.matchPath}/end", mapOf("timestamp" to startedAt.plusSeconds(1)), login), 200)
        assertEquals(listOf("COMPLETED"), get(fixture.path)["points"].map { it["status"].asText() })
    }

    @Test
    fun `planned matches have no actionable points and ordinary matches retain portable transfer`() {
        val fixture = fixture(start = false)
        assertTrue(get(fixture.path)["points"].isEmpty)
        response(send("PUT", "${fixture.path}/${UUID.randomUUID()}", fixture.selection(), login), 409)
        val transfer = response(send("POST", "/api/v1/matches/export", mapOf("matchIds" to listOf(fixture.id)), login), 200)
        assertEquals(1, transfer["formatVersion"].asInt())
        assertEquals(fixture.id.toString(), transfer["matches"].single()["id"].asText())
    }

    @Test
    fun `concurrent confirmations and PULL serialize on the same journal position`() {
        val fixture = fixture()
        val point = get(fixture.path)["points"].single()["pointId"].asText()
        val path = "${fixture.path}/$point"
        val actor = fixture.leftPlayers[0]
        // Provision credentials before starting worker requests.
        val session = login
        val pair = race(
            { send("PUT", path, fixture.selection(left = listOf(player(actor))), session) },
            { send("PUT", path, fixture.selection(right = listOf(temporary())), session) },
        )
        assertEquals(listOf(200, 409), pair.map { it.statusCode() }.sorted())
        assertEquals(response(pair.single { it.statusCode() == 200 }, 200), get(fixture.path)["points"].single())

        val pullRace = fixture()
        val pullPoint = get(pullRace.path)["points"].single()["pointId"].asText()
        val pullId = UUID.randomUUID()
        val (confirm, pull) = race(
            { send("PUT", "${pullRace.path}/$pullPoint", pullRace.selection(), session) },
            { send("POST", "${pullRace.matchPath}/events", eventBody(pullId, "PULL", pullRace.rightPlayers[0]), session) },
        )
        response(pull, 201)
        val active = get(pullRace.path)["points"].single()
        assertEquals(pullPoint, active["pointId"].asText())
        assertEquals(pullId.toString(), active["pullEventId"].asText())
        when (confirm.statusCode()) {
            200 -> assertEquals(response(confirm, 200)["lineup"], active["lineup"])
            409 -> assertTrue(active["lineup"].isNull)
            else -> error(confirm.body())
        }
    }

    protected fun verifyFreshProcessConfiguration() {
        val fixture = fixture()
        val pointId = get(fixture.path)["points"].single()["pointId"].asText()
        val path = "${fixture.path}/$pointId"
        val body = fixture.selection(left = listOf(temporary()))
        val saved = response(send("PUT", path, body, login), 200)
        event(fixture, "PULL", fixture.rightPlayers[0])
        val anotherMatch = fixture()
        val nextId = get(anotherMatch.path)["points"].single()["pointId"].asText()

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
        ).use { app ->
            val restartedPort = (app as ServletWebServerApplicationContext).webServer.port
            val log = response(send("GET", fixture.path, targetPort = restartedPort), 200)
            assertFalse(log["writesEnabled"].asBoolean())
            val active = log["points"].single()
            assertEquals("ACTIVE", active["status"].asText())
            assertEquals(saved["lineup"], active["lineup"])
            assertEquals(active, response(send("PUT", path, body, login, targetPort = restartedPort), 200))
            val denied = response(send("PUT", "${anotherMatch.path}/$nextId", anotherMatch.selection(),
                login, targetPort = restartedPort), 409)
            assertEquals("POINT_LINEUP_WRITE_DISABLED", denied["code"].asText())
        }
    }

    private data class Fixture(val id: UUID, val teamIds: List<UUID>, val leftPlayers: List<UUID>, val rightPlayers: List<UUID>) {
        val matchPath = "/api/v1/matches/$id"
        val path = "$matchPath/point-lineups"

        fun selection(revision: Long = 0, left: List<Any> = emptyList(), right: List<Any> = emptyList()) =
            mapOf("expectedLogRevision" to revision, "teams" to teamIds.zip(listOf(left, right)).map { (team, entries) ->
                mapOf("teamId" to team, "entries" to entries)
            })
    }

    private fun fixture(start: Boolean = true): Fixture {
        val teamIds = List(2) { UUID.randomUUID() }.onEach { teams.create(Team(it, "Point lineup team")) }
        val roster = teamIds.map { team ->
            List(2) { UUID.randomUUID() }.onEach {
                players.create(Player(it, "Fixture", "Player"))
                memberships.add(team, it, null)
            }
        }
        val id = UUID.randomUUID()
        matches.create(Match(id, teamIds, playersPerTeam = 2))
        if (start) matches.startMatch(id, startedAt)
        return Fixture(id, teamIds, roster[0], roster[1])
    }

    private fun player(id: UUID) = mapOf("kind" to "PLAYER", "participantId" to id)
    private fun temporary(id: UUID = UUID.randomUUID(), number: Int = 1) =
        mapOf("kind" to "TEMPORARY", "slotId" to id, "number" to number)

    private fun eventBody(id: UUID, type: String, from: UUID, to: UUID? = null) =
        mapOf("type" to type, "eventId" to id, "occurredAt" to startedAt.toString()) +
            if (to == null) mapOf("participantId" to from) else mapOf("fromParticipantId" to from, "toParticipantId" to to)

    private fun event(fixture: Fixture, type: String, from: UUID, to: UUID? = null): UUID {
        val id = UUID.randomUUID()
        response(send("POST", "${fixture.matchPath}/events", eventBody(id, type, from, to), login), 201)
        return id
    }

    private fun get(path: String): JsonNode = response(send("GET", path), 200)

    private fun response(response: HttpResponse<String>, status: Int): JsonNode {
        assertEquals(status, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun send(method: String, path: String, body: Any? = null, session: AuthLoginResult? = null,
                     csrf: Boolean = true, targetPort: Int = port): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$targetPort$path")).timeout(Duration.ofSeconds(15))
        val cookies = mutableListOf<String>()
        if (session != null) {
            val context = session.context
            cookies += "ULTISTATS_LOOPBACK_SESSION=${session.credential}"
            request.header("X-UltiStats-Installation-Id", context.installationId.toString())
                .header("X-UltiStats-Account-Id", context.account!!.id.toString())
                .header("X-UltiStats-Session-Id", context.sessionId.toString())
            if (method != "GET" && csrf) {
                val token = send("GET", "/api/v1/auth/csrf", session = session, targetPort = targetPort)
                cookies += token.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
                request.header("X-CSRF-TOKEN", response(token, 200)["token"].asText())
            }
        }
        if (cookies.isNotEmpty()) request.header("Cookie", cookies.joinToString("; "))
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else {
            request.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
        }
        return client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun race(first: () -> HttpResponse<String>, second: () -> HttpResponse<String>) =
        Executors.newFixedThreadPool(2).use { executor ->
            val gate = CountDownLatch(1)
            val futures = listOf(first, second).map { action ->
                executor.submit<HttpResponse<String>> {
                    check(gate.await(10, TimeUnit.SECONDS))
                    action()
                }
            }
            gate.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        }
}

@ActiveProfiles("postgres-list", inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class PointLineupPostgresTest : PointLineupHttpTest() {
    @Test
    fun `fresh default-off application reads and replays accepted confirmation on same PostgreSQL data`() =
        verifyFreshProcessConfiguration()
}
