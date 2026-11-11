package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
import org.springframework.jdbc.core.JdbcTemplate
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
    properties = [
        "app.auth.ingress.loopback-mode=true",
        "ultistats.point-lineups.write-enabled=true",
        "ultistats.possession-change.write-enabled=true",
    ],
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
    @Autowired private lateinit var jdbc: JdbcTemplate
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

        val pullActor = matches.getOrThrow(fixture.id).participantsByTeam.getValue(fixture.teamIds[1])
            .first { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        val pullId = event(fixture, "PULL", pullActor, capability = "1")
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
        val schemasByPath = api["paths"]
        for ((method, path) in listOf(
            "get" to "/api/v1/matches/{matchId}/events", "post" to "/api/v1/matches/{matchId}/events",
            "get" to "/api/v2/matches/{matchId}/events", "post" to "/api/v2/matches/{matchId}/events/batch",
            "post" to "/api/v2/matches/{matchId}/events/undo",
        )) {
            val operation = schemasByPath[path][method]
            val capability = operation["parameters"].single { it["name"].asText() == "X-UltiStats-Point-Lineups" }
            assertEquals("header", capability["in"].asText())
            assertEquals("1", capability["schema"]["enum"].single().asText())
            assertTrue(operation["responses"]["409"]["description"].asText().contains("POINT_LINEUP_CLIENT_REQUIRED"))
        }
        assertFalse(api["components"]["schemas"]["UndoCommandRejection"]["properties"]["code"]["enum"].any {
            it.asText().startsWith("POINT_LINEUP")
        })
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
        val patch = schemasByPath.path("/api/v1/matches/{matchId}/point-lineups/{pointId}/slots/{slotId}").path("patch")
        assertFalse(patch.isMissingNode, "The slot PATCH operation is missing from OpenAPI")
        assertEquals("#/components/schemas/PointLineupResponse",
            patch["responses"]["200"]["content"]["application/json"]["schema"]["\$ref"].asText())
        val identify = schemas["IdentifyPointLineupSlotRequest"]
        assertEquals(setOf("participantId", "expectedLineupRevision", "expectedClosingEventId"),
            identify["required"].map { it.asText() }.toSet())
        val candidateSchema = identify["properties"]["participantId"]
        assertEquals(setOf("string", "null"), candidateSchema["type"].map { it.asText() }.toSet())
        assertEquals("uuid", candidateSchema["format"].asText())
        val revisionSchema = identify["properties"]["expectedLineupRevision"]
        assertEquals("integer", revisionSchema["type"].asText())
        assertEquals(0, revisionSchema["minimum"].asInt())
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
        response(send("POST", "${fixture.matchPath}/events/undo", session = login, capability = "1"), 200)
        val undone = get(fixture.path)["points"].single()
        assertEquals(first, undone["pointId"])
        assertEquals("ACTIVE", undone["status"].asText())
        response(send("PUT", "${fixture.path}/$removed", accepted, login), 409)
        val callahan = event(fixture, "CALLAHAN", fixture.leftPlayers[0], fixture.rightPlayers[0], capability = "1")
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
        val pullActor = matches.getOrThrow(pullRace.id).participantsByTeam.getValue(pullRace.teamIds[1])
            .first { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        val (confirm, pull) = race(
            { send("PUT", "${pullRace.path}/$pullPoint", pullRace.selection(), session) },
            { send("POST", "${pullRace.matchPath}/events", eventBody(pullId, "PULL", pullActor), session, capability = "1") },
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

    @Test
    fun `raw slot actions score and keep legacy readers and writers outside the managed journal`() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val slot = UUID.randomUUID()
        val left = fixture.leftPlayers[0]
        val right = fixture.rightPlayers[0]
        confirm(fixture, listOf(player(left), temporary(slot)), listOf(player(right)))
        val pull = eventBody(UUID.randomUUID(), "PULL", right)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", slot) +
            ("occurredAt" to startedAt.plusSeconds(2).toString())
        val goal = eventBody(UUID.randomUUID(), "GOAL", slot, left) +
            ("occurredAt" to startedAt.plusSeconds(7).toString())

        response(send("POST", journal, pull, login, csrf = false, capability = "1"), 403)
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", journal, pull, login,
            capability = "true", capture = "managed-invalid-capability-v1-post"), 409)["code"].asText())
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", journal, pull - "eventId", login,
            capture = "managed-legacy-v1-post"), 409)["code"].asText())
        val rawPull = response(send("POST", journal, pull, login, capability = "1",
            capture = "managed-capable-v1-post"), 201)
        response(send("GET", journal), 200)
        response(send("GET", v2), 200)
        val batch = batch(1, 1, pickup, goal)
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", "$v2/batch", batch, login,
            capture = "managed-legacy-v2-append"), 409)["code"].asText())
        val appended = response(send("POST", "$v2/batch", batch, login, capability = "1",
            capture = "managed-capable-v2-append"), 201)
        assertEquals(slot.toString(), appended["events"][0]["participantId"].asText())
        assertEquals(slot.toString(), appended["events"][1]["fromParticipantId"].asText())
        assertEquals(appended, response(send("POST", "$v2/batch", batch, login, capability = "1"), 200))

        for ((path, name) in listOf(journal to "v1-list", "$journal/${pickup["eventId"]}" to "v1-one", v2 to "v2-log")) {
            assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("GET", path, capture = "managed-legacy-$name"), 409)["code"].asText())
            response(send("GET", path, capability = "1", capture = "managed-capable-$name"), 200)
        }
        val pullPath = "$journal/${rawPull["id"].asText()}"
        for ((method, path, body) in listOf(
            Triple("PATCH", pullPath, pull - "occurredAt" - "eventId"),
            Triple("DELETE", pullPath, null),
            Triple("POST", "$journal/undo", null),
            Triple("POST", "$v2/undo", undo(goal, 2)),
        )) {
            assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send(method, path, body, login), 409)["code"].asText())
        }
        val points = get(fixture.path)
        assertEquals(listOf("COMPLETED", "NEXT"), points["points"].map { it["status"].asText() })
        assertEquals("POINT_LINEUP_CONFLICT", response(send("POST", journal, pull + ("eventId" to UUID.randomUUID()) +
            ("occurredAt" to startedAt.plusSeconds(10).toString()), login, capability = "1"), 409)["code"].asText())
        val match = get(fixture.matchPath)
        val leftStats = response(send("GET", "${fixture.matchPath}/statistics", session = login), 200)["teams"].single { it["teamId"].asText() == fixture.teamIds[0].toString() }
        assertEquals(1, leftStats["attack"]["score"].asInt())
        assertEquals(5000, leftStats["time"]["possessionTimeMs"].asInt())
        assertTrue(leftStats["participants"].none { it["participantId"].asText() == slot.toString() })
        assertEquals(2, leftStats["participants"].count { it["kind"].asText() == "UNKNOWN" })
        assertEquals(1, match["teams"].single { it["teamId"].asText() == fixture.teamIds[0].toString() }["teamScore"].asInt())
    }

    @Test
    fun `batch validates each point and rolls back a mixed boundary failure`() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val left = UUID.randomUUID()
        val otherLeft = UUID.randomUUID()
        val right = UUID.randomUUID()
        val otherRight = UUID.randomUUID()
        confirm(fixture, listOf(temporary(left), temporary(otherLeft, 2)),
            listOf(temporary(right), temporary(otherRight, 2)))

        val pull = eventBody(UUID.randomUUID(), "PULL", right)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", left)
        val switch = eventBody(UUID.randomUUID(), "POSSESSION_SET", otherRight)
        val callahan = eventBody(UUID.randomUUID(), "CALLAHAN", otherRight, otherLeft)
        val wrongTeam = eventBody(UUID.randomUUID(), "CALLAHAN", otherRight, right)
        val rollover = eventBody(UUID.randomUUID(), "PULL", right)
        val unusedPlayer = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        assertEquals("POINT_LINEUP_CONFLICT", response(send("POST", journal, unusedPlayer, login, capability = "1"), 409)["code"].asText())
        response(send("POST", "$v2/batch", batch(0, 0, pull, pickup, switch, wrongTeam), login, capability = "1"), 400)
        val failed = response(send("POST", "$v2/batch", batch(0, 0, pull, pickup, switch, callahan, rollover),
            login, capability = "1"), 409)
        assertEquals("POINT_LINEUP_CONFLICT", failed["code"].asText())
        assertEquals(4, failed["eventIndex"].asInt())
        assertEquals(0, failed["logRevision"].asInt())
        assertTrue(get(v2)["events"].isEmpty)
        response(send("POST", "$v2/batch", batch(0, 0, pull, pickup, switch, callahan), login, capability = "1"), 201)
        val nextSlot = UUID.randomUUID()
        confirm(fixture, listOf(temporary(nextSlot)), emptyList())
        assertEquals("POINT_LINEUP_CONFLICT", response(send("POST", journal, rollover, login, capability = "1"), 409)["code"].asText())
        val closing = "$journal/${callahan["eventId"]}"
        val patch = mapOf("type" to "CALLAHAN", "toParticipantId" to nextSlot)
        assertEquals("POINT_LINEUP_CONFLICT", response(send("PATCH", closing, patch, login, capability = "1"), 409)["code"].asText())
        val unknown = matches.getOrThrow(fixture.id).participantsByTeam.getValue(fixture.teamIds[1])
            .last { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        val nextPull = eventBody(UUID.randomUUID(), "PULL", unknown)
        val nextPickup = eventBody(UUID.randomUUID(), "PICKUP", nextSlot)
        response(send("POST", "$v2/batch", batch(4, 1, nextPull, nextPickup), login, capability = "1"), 201)

        // Removing the closing boundary would move an active second-point slot to point one.
        assertEquals("POINT_LINEUP_CONFLICT", response(send("DELETE", closing, session = login,
            capability = "1"), 409)["code"].asText())
        val undo = undo(nextPickup, 2)
        val outcome = response(send("POST", "$v2/undo", undo, login, capability = "1"), 200)
        assertEquals("APPLIED", outcome["outcome"].asText())
        assertEquals(outcome, response(send("GET", "$v2/undo/${undo["commandId"]}", session = login), 200))
        val stats = response(send("GET", "${fixture.matchPath}/statistics", session = login), 200)
        val leftStats = stats["teams"].single { it["teamId"].asText() == fixture.teamIds[0].toString() }
        assertEquals(1, leftStats["attack"]["score"].asInt())
        assertEquals(1, leftStats["defense"]["callahans"].asInt())
    }

    @Test
    fun `ordinary legacy journals retain original reads and mutations`() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val pull = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", fixture.leftPlayers[0])
        response(send("POST", journal, pull - "eventId", login, capture = "ordinary-legacy-v1-post"), 201)
        response(send("POST", "$v2/batch", batch(1, 1, pickup), login, capture = "ordinary-legacy-v2-append"), 201)
        response(send("GET", fixture.matchPath, capture = "ordinary-legacy-match"), 200)
        val v1 = response(send("GET", journal, capture = "ordinary-legacy-v1-list"), 200)
        val v2Log = response(send("GET", v2, capture = "ordinary-legacy-v2-log"), 200)
        assertEquals(v1, v2Log["events"])
        assertEquals("APPLIED", response(send("POST", "$v2/undo", undo(pickup, 2), login,
            capture = "ordinary-legacy-v2-undo"), 200)["outcome"].asText())
        response(send("POST", "$journal/undo", session = login, capture = "ordinary-legacy-v1-undo"), 200)
        assertTrue(get(v2)["events"].isEmpty)
    }

    @Test
    fun `identification and explicit null undo only change personal attribution`() {
        val fixture = fixture()
        val direct = fixture.leftPlayers[0]
        val candidate = fixture.leftPlayers[1]
        val slot = UUID.randomUUID()
        val right = fixture.rightPlayers[0]
        val first = confirm(fixture, listOf(temporary(slot), player(direct)), listOf(player(right)))
        val pull = eventBody(UUID.randomUUID(), "PULL", right)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", slot).at(2)
        val goal = eventBody(UUID.randomUUID(), "GOAL", slot, direct).at(7)
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val append = batch(0, 0, pull, pickup, goal)
        val receipt = response(send("POST", "$journal/batch", append, login, capability = "1"), 201)

        confirm(fixture, listOf(player(candidate), player(direct)), listOf(player(right)))
        response(send("POST", "$journal/batch", batch(3, 1, pull.withNewId().at(10),
            eventBody(UUID.randomUUID(), "PICKUP", candidate).at(12),
            eventBody(UUID.randomUUID(), "GOAL", candidate, direct).at(15)), login, capability = "1"), 201)

        val path = slotPath(fixture, first, slot)
        val closing = goal.getValue("eventId")
        val body = identification(candidate, 1, closing)
        val points = get(fixture.path)
        val match = get(fixture.matchPath)
        val raw = response(send("GET", journal, capability = "1", capture = "identification-journal-before"), 200)
        val database = journalRows(fixture)
        val statsPath = "${fixture.matchPath}/statistics"
        val beforeResponse = send("GET", statsPath, session = login, capture = "identification-stats-before")
        val before = response(beforeResponse, 200)
        val beforePlayer = participant(before, candidate)
        assertEquals(3000, beforePlayer["time"]["possessionTimeMs"].asInt())
        assertEquals(1, beforePlayer["attack"]["assists"].asInt())

        response(send("PATCH", path, body), 401)
        assertEquals("CSRF_INVALID", response(send("PATCH", path, body, login, csrf = false), 403)["code"].asText())
        val identified = response(send("PATCH", path, body, login, capture = "identification-success"), 200)
        assertEquals(candidate.toString(), resolution(identified))
        assertEquals(2, identified["lineup"]["revision"].asInt())
        assertEquals(closing.toString(), identified["closingEventId"].asText())
        val refreshed = response(send("GET", fixture.path, capture = "identification-point-after"), 200)
        assertEquals(identified, refreshed["points"].first())
        assertEquals(points["points"].drop(1), refreshed["points"].drop(1))
        assertEquals(points["logRevision"], refreshed["logRevision"])

        val afterResponse = send("GET", statsPath, session = login, capture = "identification-stats-after")
        val after = response(afterResponse, 200)
        assertEquals(beforeResponse.headers().firstValue(ANNOUNCEMENT_STATISTICS_HEADER),
            afterResponse.headers().firstValue(ANNOUNCEMENT_STATISTICS_HEADER))
        val afterPlayer = participant(after, candidate)
        assertEquals("PLAYER", afterPlayer["kind"].asText())
        assertEquals(8000, afterPlayer["time"]["possessionTimeMs"].asInt())
        assertEquals(4000, afterPlayer["time"]["averagePossessionTimeMs"].asInt())
        assertEquals(2, afterPlayer["attack"]["assists"].asInt())
        assertEquals(2, afterPlayer["attack"]["discPossessions"].asInt())
        assertEquals(before, after.deepCopy<JsonNode>().also { copy ->
            val participants = copy["teams"].flatMap { it["participants"] }
            (participants.single { it["participantId"].asText() == candidate.toString() } as ObjectNode)
                .setAll<ObjectNode>(beforePlayer as ObjectNode)
        })

        assertEquals(raw, response(send("GET", journal, capability = "1", capture = "identification-journal-bound"), 200))
        assertEquals(database, journalRows(fixture))
        assertEquals(match, get(fixture.matchPath))
        assertEquals(identified, response(send("PATCH", path, identification(candidate, 2, closing), login), 200))
        assertConflict(send("PATCH", path, body, login, capture = "identification-stale"))
        val undone = response(send("PATCH", path, identification(null, 2, closing), login, capture = "identification-undo"), 200)
        assertEquals(null, resolution(undone))
        assertEquals(3, undone["lineup"]["revision"].asInt())
        assertConflict(send("PATCH", path, body, login))
        assertEquals(undone, response(send("PATCH", path, identification(null, 3, closing), login), 200))
        assertEquals(undone, get(fixture.path)["points"].first())
        assertEquals(before, response(send("GET", statsPath, session = login, capture = "identification-stats-undone"), 200))
        assertEquals(raw, response(send("GET", journal, capability = "1", capture = "identification-journal-after"), 200))
        assertEquals(database, journalRows(fixture))
        val replay = response(send("POST", "$journal/batch", append, login, capability = "1"), 200)
        assertEquals(receipt["events"], replay["events"])
        assertEquals(points["logRevision"], replay["logRevision"])
        assertEquals(match, get(fixture.matchPath))
    }

    @Test
    fun `identification validates request state and match team and serializes competing assignments`() {
        val fixture = fixture()
        val firstSlot = UUID.randomUUID()
        val secondSlot = UUID.randomUUID()
        val right = fixture.rightPlayers[0]
        val candidate = fixture.leftPlayers[0]
        val first = confirm(fixture, listOf(temporary(firstSlot), temporary(secondSlot, 2)), listOf(player(right)))
        val path = slotPath(fixture, first, firstSlot)
        val secondPath = slotPath(fixture, first, secondSlot)
        val goalId = UUID.randomUUID()
        val body = identification(candidate, 1, goalId)
        assertConflict(send("PATCH", path, body, login))
        event(fixture, "PULL", right, capability = "1")
        assertConflict(send("PATCH", path, body, login))
        event(fixture, "PICKUP", firstSlot, capability = "1")
        response(send("POST", "${fixture.matchPath}/events", eventBody(goalId, "GOAL", firstSlot, secondSlot),
            login, capability = "1"), 201)
        val completed = get(fixture.path)["points"].first()
        val nextSlot = UUID.randomUUID()
        val next = confirm(fixture, emptyList(), listOf(temporary(nextSlot)))
        for (badBody in listOf(
            body - "participantId", body - "expectedLineupRevision", body - "expectedClosingEventId",
            body + ("expectedLineupRevision" to NullNode.instance), body + ("expectedClosingEventId" to NullNode.instance),
            body + ("participantId" to ""), body + ("expectedClosingEventId" to ""),
            body + ("expectedLineupRevision" to "1"), body + ("expectedLineupRevision" to -1),
            body + ("expectedLineupRevision" to 1.0), body + ("expectedLineupRevision" to true),
        )) response(send("PATCH", path, badBody, login), 400)

        val unknown = matches.getOrThrow(fixture.id).participantsByTeam.getValue(fixture.teamIds[0])
            .first { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        for (ineligible in listOf(unknown, UUID.randomUUID(), fixture.rightPlayers[1])) {
            assertConflict(send("PATCH", path, identification(ineligible, 1, goalId), login))
        }
        for (invalidPath in listOf(slotPath(fixture, first, UUID.randomUUID()), slotPath(fixture, first, nextSlot),
            slotPath(fixture, next, firstSlot), "${fixture.path}/${UUID.randomUUID()}/slots/$firstSlot")) {
            assertConflict(send("PATCH", invalidPath, body, login))
        }
        assertConflict(send("PATCH", path, identification(candidate, 1, UUID.randomUUID()), login))
        assertEquals(completed, get(fixture.path)["points"].first())
        val results = race({ send("PATCH", path, body, login) }, { send("PATCH", secondPath, body, login) })
        assertEquals(listOf(200, 409), results.map { it.statusCode() }.sorted())
        assertConflict(results.single { it.statusCode() == 409 })
        val winner = response(results.single { it.statusCode() == 200 }, 200)
        assertEquals(2, winner["lineup"]["revision"].asInt())
        val entries = winner["lineup"]["teams"][0]["entries"]
        val unoccupied = UUID.fromString(entries.single { it["resolvedParticipantId"].isNull }["slotId"].asText())
        assertConflict(send("PATCH", slotPath(fixture, winner, unoccupied), identification(candidate, 2, goalId), login))
        assertEquals(winner, get(fixture.path)["points"].first())
    }

    @Test
    fun `old closing event conflicts after gameplay undo and recompletion and finished points can be corrected`() {
        val fixture = fixture()
        val slot = UUID.randomUUID()
        val direct = fixture.leftPlayers[0]
        val candidate = fixture.leftPlayers[1]
        val right = fixture.rightPlayers[0]
        val rightSlot = UUID.randomUUID()
        val first = confirm(fixture, listOf(temporary(slot), player(direct)), listOf(temporary(rightSlot), player(right)))
        val path = slotPath(fixture, first, slot)
        val rightPath = slotPath(fixture, first, rightSlot)
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val goal = eventBody(UUID.randomUUID(), "GOAL", slot, direct).at(7)
        val priorClosing = goal.getValue("eventId")
        response(send("POST", "$v2/batch", batch(0, 0, eventBody(UUID.randomUUID(), "PULL", rightSlot),
            eventBody(UUID.randomUUID(), "PICKUP", slot).at(2), goal), login, capability = "1"), 201)
        assertConflict(send("PATCH", path, identification(direct, 1, priorClosing), login))
        assertConflict(send("PATCH", rightPath, identification(right, 1, priorClosing), login))
        val undo = undo(goal, 1)
        val undoReceipt = response(send("POST", "$v2/undo", undo, login, capability = "1"), 200)
        assertEquals("APPLIED", undoReceipt["outcome"].asText())
        assertConflict(send("PATCH", path, identification(candidate, 1, priorClosing), login))
        val newGoal = goal.withNewId()
        val closing = newGoal.getValue("eventId")
        response(send("POST", journal, newGoal, login, capability = "1"), 201)
        assertEquals(first["pointId"], get(fixture.path)["points"].first()["pointId"])
        assertConflict(send("PATCH", path, identification(candidate, 1, priorClosing), login,
            capture = "identification-stale-closing"))
        response(send("POST", "${fixture.matchPath}/end", mapOf("timestamp" to startedAt.plusSeconds(10)), login), 200)
        assertEquals("FINISHED", get(fixture.matchPath)["status"].asText())
        val journalBefore = journalRows(fixture)
        assertEquals(candidate.toString(), resolution(response(send("PATCH", path, identification(candidate, 1, closing), login), 200)))
        val rightCandidate = fixture.rightPlayers[1]
        val rightIdentified = response(send("PATCH", rightPath, identification(rightCandidate, 2, closing), login), 200)
        assertEquals(rightCandidate.toString(), rightIdentified["lineup"]["teams"][1]["entries"][0]["resolvedParticipantId"].asText())
        assertEquals(1, participant(get("${fixture.matchPath}/statistics"), rightCandidate)["attack"]["pulls"].asInt())
        assertEquals(rightIdentified, get(fixture.path)["points"].single())
        assertEquals(journalBefore, journalRows(fixture))
        assertEquals(undoReceipt, response(send("GET", "$v2/undo/${undo["commandId"]}", session = login), 200))
    }

    protected fun verifyFreshProcessIdentification() {
        val fixture = fixture()
        val slot = UUID.randomUUID()
        val candidate = fixture.leftPlayers[1]
        val direct = fixture.leftPlayers[0]
        val right = fixture.rightPlayers[0]
        val first = confirm(fixture, listOf(temporary(slot), player(direct)), listOf(player(right)))
        val path = slotPath(fixture, first, slot)
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val statsPath = "${fixture.matchPath}/statistics"
        val goal = eventBody(UUID.randomUUID(), "GOAL", slot, direct).at(7)
        val closing = goal.getValue("eventId")
        val batch = batch(0, 0, eventBody(UUID.randomUUID(), "PULL", right),
            eventBody(UUID.randomUUID(), "PICKUP", slot).at(2), goal)
        val receipt = response(send("POST", "$journal/batch", batch, login, capability = "1"), 201)
        val before = participant(response(send("GET", statsPath, session = login), 200), candidate)
        assertEquals(0, before["time"]["possessionTimeMs"].asInt())
        assertEquals(0, before["attack"]["assists"].asInt())
        val identified = response(send("PATCH", path, identification(candidate, 1, closing), login), 200)
        val stats = response(send("GET", statsPath, session = login), 200)
        assertEquals(5000, participant(stats, candidate)["time"]["possessionTimeMs"].asInt())
        assertEquals(1, participant(stats, candidate)["attack"]["assists"].asInt())
        val raw = response(send("GET", journal, capability = "1"), 200)
        val database = journalRows(fixture)

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
            "--ultistats.point-lineups.write-enabled=false",
        ).use { app ->
            val restartedPort = (app as ServletWebServerApplicationContext).webServer.port
            for ((label, value) in listOf("undo" to null, "replacement" to direct, "no-op" to candidate)) {
                val rejected = response(send("PATCH", path, identification(value, 2, closing), login,
                    targetPort = restartedPort, capture = "identification-off-$label"), 409)
                assertEquals("POINT_LINEUP_WRITE_DISABLED", rejected["code"].asText())
            }
            val reloaded = response(send("GET", fixture.path, targetPort = restartedPort, capture = "identification-off-points"), 200)
            assertFalse(reloaded["writesEnabled"].asBoolean())
            assertEquals(identified, reloaded["points"].first())
            assertEquals(stats, response(send("GET", statsPath, session = login, targetPort = restartedPort,
                capture = "identification-off-stats"), 200))
            assertEquals(raw, response(send("GET", journal, capability = "1", targetPort = restartedPort), 200))
            assertEquals(receipt, response(send("POST", "$journal/batch", batch, login, capability = "1", targetPort = restartedPort), 200))
            assertEquals(database, journalRows(fixture))
            val safeUndo = response(send("POST", "$journal/undo", undo(goal, 1), login, capability = "1",
                targetPort = restartedPort, capture = "identification-off-gameplay-undo"), 200)
            assertEquals("APPLIED", safeUndo["outcome"].asText())
        }
    }

    private fun slotPath(fixture: Fixture, point: JsonNode, slot: UUID) =
        "${fixture.path}/${point["pointId"].asText()}/slots/$slot"

    private fun identification(candidate: UUID?, revision: Long, closing: Any) =
        mapOf("participantId" to (candidate ?: NullNode.instance), "expectedLineupRevision" to revision, "expectedClosingEventId" to closing)

    private fun resolution(point: JsonNode) =
        point["lineup"]["teams"][0]["entries"][0]["resolvedParticipantId"].let { if (it.isNull) null else it.asText() }

    private fun participant(stats: JsonNode, id: UUID) =
        stats["teams"].flatMap { it["participants"] }.single { it["participantId"].asText() == id.toString() }

    private fun assertConflict(result: HttpResponse<String>) =
        assertEquals("POINT_LINEUP_CONFLICT", response(result, 409)["code"].asText())

    private fun Map<String, Any>.at(seconds: Long) = this + ("occurredAt" to startedAt.plusSeconds(seconds).toString())

    private fun Map<String, Any>.withNewId() = this + ("eventId" to UUID.randomUUID())

    private fun journalRows(fixture: Fixture) = listOf("events", "event_batch_receipts", "undo_command_receipts").map { table ->
        jdbc.queryForList("SELECT * FROM $table WHERE match_id = ? ORDER BY id", fixture.id).map { row ->
            row.mapValues { (_, value) -> value?.toString() }
        }
    }

    private fun confirm(fixture: Fixture, left: List<Any>, right: List<Any>): JsonNode {
        val snapshot = get(fixture.path)
        val pointId = snapshot["points"].last()["pointId"].asText()
        return response(send("PUT", "${fixture.path}/$pointId",
            fixture.selection(snapshot["logRevision"].asLong(), left, right), login), 200)
    }

    private fun batch(sequence: Int, revision: Long, vararg events: Map<String, Any>) =
        mapOf("expectedLastSequenceNumber" to sequence, "expectedLogRevision" to revision, "events" to events)

    private fun undo(event: Map<String, Any>, revision: Long) =
        mapOf("commandId" to UUID.randomUUID(), "expectedLastEventId" to event.getValue("eventId"), "expectedLogRevision" to revision)

    protected fun verifyFreshProcessGameplay() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val leftSlot = UUID.randomUUID()
        val rightSlot = UUID.randomUUID()
        confirm(fixture, listOf(temporary(leftSlot), player(fixture.leftPlayers[0])), listOf(temporary(rightSlot)))
        val pull = eventBody(UUID.randomUUID(), "PULL", rightSlot)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", leftSlot) + ("occurredAt" to startedAt.plusSeconds(3).toString())
        val goal = eventBody(UUID.randomUUID(), "GOAL", leftSlot, fixture.leftPlayers[0]) +
            ("occurredAt" to startedAt.plusSeconds(9).toString())
        val batch = batch(1, 1, pickup, goal)
        val first = response(send("POST", journal, pull, login, capability = "1"), 201)
        val committed = response(send("POST", "$v2/batch", batch, login, capability = "1"), 201)
        val original = response(send("GET", v2, capability = "1"), 200)
        val stats = "${fixture.matchPath}/statistics"
        val originalStats = response(send("GET", stats, session = login, capture = "rollback-enabled-statistics"), 200)
        val originalMatch = get(fixture.matchPath)
        val nextSlot = UUID.randomUUID()
        confirm(fixture, listOf(temporary(nextSlot)), emptyList())
        val fresh = eventBody(UUID.randomUUID(), "PULL", nextSlot) +
            ("occurredAt" to startedAt.plusSeconds(10).toString())

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
        ).use { app ->
            val restartedPort = (app as ServletWebServerApplicationContext).webServer.port
            assertEquals(original, response(send("GET", v2, targetPort = restartedPort, capability = "1",
                capture = "rollback-disabled-capable-v2-log"), 200))
            assertEquals(original["events"], response(send("GET", journal, targetPort = restartedPort, capability = "1",
                capture = "rollback-disabled-capable-v1-list"), 200))
            assertEquals(originalStats, response(send("GET", stats, session = login, targetPort = restartedPort,
                capture = "rollback-disabled-statistics"), 200))
            assertEquals(originalMatch, response(send("GET", fixture.matchPath, targetPort = restartedPort), 200))
            assertEquals(first, response(send("POST", journal, pull, login, targetPort = restartedPort, capability = "1",
                capture = "rollback-disabled-v1-retry"), 200))
            assertEquals(committed, response(send("POST", "$v2/batch", batch, login, targetPort = restartedPort, capability = "1",
                capture = "rollback-disabled-v2-retry"), 200))
            assertEquals("POINT_LINEUP_WRITE_DISABLED", response(send("POST", "$v2/batch", batch(3, 2, fresh), login,
                targetPort = restartedPort, capability = "1", capture = "rollback-disabled-v2-fresh"), 409)["code"].asText())
            assertEquals("POINT_LINEUP_WRITE_DISABLED", response(send("POST", journal, fresh, login,
                targetPort = restartedPort, capability = "1", capture = "rollback-disabled-v1-fresh"), 409)["code"].asText())
            assertEquals("APPLIED", response(send("POST", "$v2/undo", undo(goal, 2), login, targetPort = restartedPort,
                capability = "1", capture = "rollback-disabled-safe-undo"), 200)["outcome"].asText())
            assertEquals(2, response(send("GET", v2, targetPort = restartedPort, capability = "1"), 200)["events"].size())
        }
    }

    protected fun verifyFreshProcessConfiguration() {
        val fixture = fixture()
        val pointId = get(fixture.path)["points"].single()["pointId"].asText()
        val path = "${fixture.path}/$pointId"
        val body = fixture.selection(left = listOf(temporary()), right = listOf(player(fixture.rightPlayers[0])))
        val saved = response(send("PUT", path, body, login), 200)
        event(fixture, "PULL", fixture.rightPlayers[0], capability = "1")
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

    private fun event(fixture: Fixture, type: String, from: UUID, to: UUID? = null, capability: String? = null): UUID {
        val id = UUID.randomUUID()
        response(send("POST", "${fixture.matchPath}/events", eventBody(id, type, from, to), login, capability = capability), 201)
        return id
    }

    private fun get(path: String): JsonNode = response(send("GET", path), 200)

    private fun response(response: HttpResponse<String>, status: Int): JsonNode {
        assertEquals(status, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun send(method: String, path: String, body: Any? = null, session: AuthLoginResult? = null,
                     csrf: Boolean = true, targetPort: Int = port, capability: String? = null,
                     capture: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$targetPort$path")).timeout(Duration.ofSeconds(15))
        capability?.let { request.header("X-UltiStats-Point-Lineups", it) }
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
        val result = client.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (capture != null) System.getenv("POINT_LINEUP_EVENT_ARTIFACT_DIR")?.let { directory ->
            val dir = Files.createDirectories(Path.of(directory))
            Files.writeString(dir.resolve("$capture.json"), result.body())
            Files.writeString(dir.resolve("$capture.headers"), buildString {
                appendLine("$method $path")
                capability?.let { appendLine("X-UltiStats-Point-Lineups: $it") }
                appendLine("HTTP ${result.statusCode()}")
                result.headers().map().filterKeys { !it.equals("Set-Cookie", ignoreCase = true) }
                    .forEach { (key, values) -> values.forEach { appendLine("$key: $it") } }
            })
            body?.let { Files.writeString(dir.resolve("$capture.request.json"), mapper.writeValueAsString(it)) }
        }
        return result
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

    @Test
    fun `fresh default-off application preserves identification and statistics on same PostgreSQL data`() =
        verifyFreshProcessIdentification()

    @Test
    fun `fresh default-off application preserves slot log score and stats and permits exact retries and safe undo`() =
        verifyFreshProcessGameplay()
}
