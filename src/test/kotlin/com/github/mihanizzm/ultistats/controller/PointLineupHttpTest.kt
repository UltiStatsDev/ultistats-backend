package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
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
import com.github.mihanizzm.ultistats.service.statistics.StatisticsService
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
        "ultistats.point-substitutions.write-enabled=true",
        "ultistats.point-incoming-reserves.write-enabled=true",
        "ultistats.possession-change.write-enabled=true",
        "ultistats.announcements.write-enabled=true",
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
    @Autowired private lateinit var statistics: StatisticsService
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
        val portable = export(fixture.id)
        assertEquals(4, portable["formatVersion"].asInt())
        val lineup = portable["matches"].single()["pointLineups"].single()
        assertTrue(lineup["openingEventId"].isNull)
        assertEquals(saved["lineup"]["teams"], lineup["teams"])

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
            assertEquals(listOf("1", "2", "3"), capability["schema"]["enum"].map { it.asText() })
            assertTrue(operation["responses"]["409"]["description"].asText().contains("POINT_LINEUP_CLIENT_REQUIRED"))
        }
        assertFalse(api["components"]["schemas"]["UndoCommandRejection"]["properties"]["code"]["enum"].any {
            it.asText().startsWith("POINT_LINEUP")
        })
        val batchDiscriminator = api["components"]["schemas"]["BatchEventRequest"]["discriminator"]["mapping"]
        assertEquals("#/components/schemas/PlayerSubstitutionBatchEvent", batchDiscriminator["PLAYER_SUBSTITUTION"].asText())
        assertFalse(api["components"]["schemas"]["CreateEventRequest"]["discriminator"]["mapping"].has("PLAYER_SUBSTITUTION"))
        assertEquals(setOf("type", "eventId", "occurredAt", "fromParticipantId", "toParticipantId"),
            api["components"]["schemas"]["PlayerSubstitutionBatchEvent"]["required"].map { it.asText() }.toSet())
        val reserveMarker = api["components"]["schemas"]["PlayerSubstitutionBatchEvent"]["properties"]["createIncomingReserve"]
        assertEquals(listOf(true), reserveMarker["enum"].map { it.asBoolean() })
        assertFalse(api["components"]["schemas"]["TwoPlayerEventRequest"]["properties"].has("createIncomingReserve"))
        val getResponseSchema = api["paths"]["/api/v1/matches/{matchId}/point-lineups"]["get"]["responses"]
            .path("200").path("content").path("application/json").path("schema")
        assertEquals("#/components/schemas/PointLineupsResponse", getResponseSchema.path("\$ref").asText())
        val schemas = api["components"]["schemas"]
        val pointSchema = schemas["PointLineupResponse"]
        val temporarySchema = schemas["PointLineupTemporary"]
        assertTrue(schemas["PointLineupsResponse"]["required"].any { it.asText() == "incomingReservesWriteEnabled" })
        assertTrue(pointSchema["required"].any { it.asText() == "substitutionSlots" })
        assertEquals("#/components/schemas/PointSubstitutionSlot",
            pointSchema["properties"]["substitutionSlots"]["items"]["\$ref"].asText())
        assertTrue(pointSchema["required"].any { it.asText() == "lineup" })
        assertTrue(pointSchema["properties"]["lineup"]["anyOf"].any { it["type"]?.asText() == "null" })
        assertTrue(temporarySchema["required"].any { it.asText() == "resolvedParticipantId" })
        assertTrue(temporarySchema.path("properties").path("resolvedParticipantId").path("type").any { it.asText() == "null" })
        val patch = schemasByPath.path("/api/v1/matches/{matchId}/point-lineups/{pointId}/slots/{slotId}").path("patch")
        assertFalse(patch.isMissingNode, "The slot PATCH operation is missing from OpenAPI")
        assertEquals("#/components/schemas/PointLineupResponse",
            patch["responses"]["200"]["content"]["application/json"]["schema"]["\$ref"].asText())
        assertTrue(schemas["PortableEvent"]["properties"]["type"]["enum"].any { it.asText() == "PLAYER_SUBSTITUTION" })
        val portableMatch = schemas["PortableMatch"]
        assertFalse(portableMatch["required"].any { it.asText() == "pointLineups" })
        assertEquals("#/components/schemas/PortablePointLineup",
            portableMatch["properties"]["pointLineups"]["items"]["\$ref"].asText())
        assertEquals(5, schemas["MatchTransferPackage"]["properties"]["formatVersion"]["maximum"].asInt())
        for ((schema, field) in listOf("PortablePointLineup" to "openingEventId", "PortablePointLineupTemporary" to "resolvedParticipantId")) {
            assertTrue(schemas[schema]["required"].any { it.asText() == field })
            assertEquals(setOf("string", "null"), schemas[schema]["properties"][field]["type"].map { it.asText() }.toSet())
        }
        val identify = schemas["IdentifyPointLineupSlotRequest"]
        assertEquals(setOf("participantId", "expectedLineupRevision", "expectedClosingEventId"),
            identify["required"].map { it.asText() }.toSet())
        val candidateSchema = identify["properties"]["participantId"]
        assertEquals(setOf("string", "null"), candidateSchema["type"].map { it.asText() }.toSet())
        assertEquals("uuid", candidateSchema["format"].asText())
        val revisionSchema = identify["properties"]["expectedLineupRevision"]
        assertEquals("integer", revisionSchema["type"].asText())
        assertEquals(0, revisionSchema["minimum"].asInt())
        for (prefix in listOf("PointLineup", "ConfirmPointLineup", "PortablePointLineup")) {
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
        val portable = export(fixture.id)
        assertEquals(1, portable["formatVersion"].asInt())
        assertFalse(portable["matches"].single().has("pointLineups"))
        assertFalse(portable["matches"].single()["events"].any { it["id"].asText() == goal.toString() })
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
        assertFalse(transfer["matches"].single().has("pointLineups"))
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
        assertEquals(v2Log, response(send("GET", v2, capability = "1", capture = "ordinary-v1-header-v2-log"), 200))
        assertEquals(v2Log["events"], response(send("GET", journal, capability = "1", capture = "ordinary-v1-header-v1-list"), 200))
        assertEquals(v2Log["events"].last(), response(send("POST", "$v2/batch", batch(1, 1, pickup), login,
            capability = "1", capture = "ordinary-v1-header-v2-replay"), 200)["events"].single())
        assertEquals("APPLIED", response(send("POST", "$v2/undo", undo(pickup, 2), login,
            capture = "ordinary-legacy-v2-undo"), 200)["outcome"].asText())
        response(send("POST", "$journal/undo", session = login, capture = "ordinary-legacy-v1-undo"), 200)
        assertTrue(get(v2)["events"].isEmpty)
        response(send("POST", journal, pull - "eventId", login, capability = "1", capture = "ordinary-v1-header-v1-post"), 201)
        response(send("POST", "$v2/batch", batch(3, 5, pickup.withNewId()), login,
            capability = "1", capture = "ordinary-v1-header-v2-append"), 201)
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

    @Test
    fun `portable v4 copies mixed all temporary and next points with reversible attribution`() {
        val fixture = fixture()
        val direct = fixture.leftPlayers[0]
        val candidate = fixture.leftPlayers[1]
        val leftSlot = UUID.randomUUID()
        val rightSlot = UUID.randomUUID()
        val first = confirm(fixture, listOf(temporary(leftSlot), player(direct)), listOf(temporary(rightSlot)))
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val exact = startedAt.plusSeconds(1).plusNanos(123456789)
        val firstGoal = eventBody(UUID.randomUUID(), "GOAL", leftSlot, direct).at(6)
        val closing = firstGoal.getValue("eventId").toString()
        response(send("POST", "$journal/batch", batch(0, 0,
            eventBody(UUID.randomUUID(), "PULL", rightSlot) + ("occurredAt" to exact.toString()),
            eventBody(UUID.randomUUID(), "POSSESSION_SET", leftSlot).at(2), firstGoal), login, capability = "1"), 201)
        response(send("PATCH", slotPath(fixture, first, leftSlot), identification(candidate, 1, closing), login), 200)

        val secondLeft = UUID.randomUUID()
        val secondRight = UUID.randomUUID()
        val secondRightReceiver = UUID.randomUUID()
        confirm(fixture, listOf(temporary(secondLeft)), listOf(temporary(secondRight), temporary(secondRightReceiver, 2)))
        val secondGoal = eventBody(UUID.randomUUID(), "GOAL", secondRight, secondRightReceiver).at(13)
        response(send("POST", "$journal/batch", batch(3, 1,
            eventBody(UUID.randomUUID(), "PULL", secondLeft).at(9),
            eventBody(UUID.randomUUID(), "PICKUP", secondRight).at(10), secondGoal), login, capability = "1"), 201)
        val announcement = addAnnouncement(fixture)
        val next = confirm(fixture, emptyList(), listOf(temporary()))
        val live = export(fixture.id, "transfer-v4-next")
        assertEquals(listOf(null, closing, secondGoal.getValue("eventId").toString()),
            live["matches"].single()["pointLineups"].map { it["openingEventId"].takeUnless(JsonNode::isNull)?.asText() })
        val liveId = mapped(importPackage(live), "matchIds", fixture.id)
        val livePoints = get("/api/v1/matches/$liveId/point-lineups")["points"]
        assertEquals("NEXT", livePoints.last()["status"].asText())
        assertNotEquals(next["pointId"], livePoints.last()["pointId"])
        assertTrue(livePoints.last()["lineup"]["teams"][0]["entries"].isEmpty)

        response(send("POST", "${fixture.matchPath}/end", mapOf("timestamp" to startedAt.plusSeconds(15)), login), 200)
        val sourcePoints = get(fixture.path)
        val source = export(fixture.id, "transfer-v4-source")
        val sourceMatch = source["matches"].single()
        assertEquals(4, source["formatVersion"].asInt())
        assertEquals(2, sourceMatch["pointLineups"].size())
        assertEquals(announcement.toString(), sourceMatch["announcements"].single()["id"].asText())
        val result = importPackage(source, capture = "transfer-v4-import")
        val copyId = mapped(result, "matchIds", fixture.id)
        val copyPath = "/api/v1/matches/$copyId"
        val copy = export(copyId, "transfer-v4-copy")["matches"].single()
        assertEquals(sourceMatch["events"].map { it["type"] }, copy["events"].map { it["type"] })
        assertEquals(sourceMatch["events"].map { it["occurredAt"] }, copy["events"].map { it["occurredAt"] })
        assertEquals(exact.toString(), copy["events"][0]["occurredAt"].asText())
        for (key in listOf("id", "startedAt", "endedAt")) {
            assertEquals(sourceMatch["announcements"][0][key], copy["announcements"][0][key])
        }
        assertEquals(mapped(result, "teamIds", fixture.teamIds[0]).toString(), copy["announcements"][0]["teamId"].asText())

        val copyPoints = response(send("GET", "$copyPath/point-lineups", capture = "transfer-v4-copy-points"), 200)["points"]
        assertEquals(listOf("COMPLETED", "COMPLETED"), copyPoints.map { it["status"].asText() })
        assertTrue(copyPoints.all { it["lineup"]["revision"].asInt() == 1 })
        assertNotEquals(first["pointId"], copyPoints.first()["pointId"])
        val newClosing = mapped(result, "eventIds", UUID.fromString(closing))
        assertEquals(newClosing.toString(), copyPoints.first()["closingEventId"].asText())
        assertEquals(newClosing.toString(), copy["pointLineups"][1]["openingEventId"].asText())
        val newCandidate = mapped(result, "playerIds", candidate)
        assertEquals(newCandidate.toString(), resolution(copyPoints.first()))
        val newSlot = UUID.fromString(copyPoints.first()["lineup"]["teams"][0]["entries"][0]["slotId"].asText())
        assertNotEquals(leftSlot, newSlot)
        assertEquals(newSlot.toString(), copy["events"][1]["fromParticipantId"].asText())
        assertEquals(newSlot.toString(), copy["events"][2]["fromParticipantId"].asText())
        val secondActors = copyPoints[1]["lineup"]["teams"][1]["entries"].map { it["slotId"].asText() }
        assertEquals(secondActors, listOf(copy["events"][5]["fromParticipantId"].asText(), copy["events"][5]["toParticipantId"].asText()))

        val sourceStats = get("${fixture.matchPath}/statistics")
        val copyStats = response(send("GET", "$copyPath/statistics", capture = "transfer-v4-copy-stats"), 200)
        assertEquals(listOf(1, 1), get(copyPath)["teams"].map { it["teamScore"].asInt() })
        assertEquals(sourceStats["time"], copyStats["time"])
        for (index in 0..1) for (key in listOf("attack", "defense", "time")) {
            assertEquals(sourceStats["teams"][index][key], copyStats["teams"][index][key])
            assertEquals(participant(sourceStats, candidate)[key], participant(copyStats, newCandidate)[key])
        }
        assertEquals(1, participant(copyStats, newCandidate)["attack"]["assists"].asInt())
        val path = "$copyPath/point-lineups/${copyPoints.first()["pointId"].asText()}/slots/$newSlot"
        val undone = response(send("PATCH", path, identification(null, 1, newClosing), login, capture = "transfer-v4-copy-undo"), 200)
        assertEquals(2, undone["lineup"]["revision"].asInt())
        assertEquals(null, resolution(undone))
        assertEquals(0, participant(get("$copyPath/statistics"), newCandidate)["attack"]["assists"].asInt())
        assertEquals(copy["events"], export(copyId)["matches"].single()["events"])
        assertEquals(sourcePoints, get(fixture.path))
        assertEquals(source, export(fixture.id))
    }

    @Test
    fun `portable v4 rejects malformed boundaries slots and resolutions before database writes`() {
        val fixture = fixture()
        val slot = UUID.randomUUID()
        val other = UUID.randomUUID()
        val direct = fixture.leftPlayers[0]
        val right = fixture.rightPlayers[0]
        confirm(fixture, listOf(temporary(slot), player(direct)), listOf(player(right)))
        val pull = event(fixture, "PULL", right, capability = "1")
        event(fixture, "PICKUP", slot, capability = "1")
        event(fixture, "GOAL", slot, direct, capability = "1")
        confirm(fixture, listOf(temporary(other)), emptyList())
        val source = export(fixture.id)
        val legacy = fixture(start = false)
        val mixed = response(send("POST", "/api/v1/matches/export",
            mapOf("matchIds" to listOf(legacy.id, fixture.id)), login), 200) as ObjectNode
        assertEquals(4, mixed["formatVersion"].asInt())
        assertFalse(mixed["matches"][0].has("pointLineups"))
        assertEquals(2, importPackage(mixed)["matchIds"].size())
        val count = transferCounts()
        val changes: List<(JsonNode, ObjectNode, ObjectNode) -> Unit> = listOf(
            { match, _, _ -> (match["pointLineups"][1] as ObjectNode).put("openingEventId", pull.toString()) },
            { match, _, _ -> (match["pointLineups"][1] as ObjectNode).putNull("openingEventId") },
            { match, _, _ -> (match["pointLineups"][1]["teams"][0]["entries"][0] as ObjectNode).put("slotId", slot.toString()) },
            { match, _, _ -> (match["events"][1] as ObjectNode).put("fromParticipantId", other.toString()) },
            { _, team, _ -> team.put("teamId", fixture.teamIds[1].toString()) },
            { _, _, entry -> entry.put("resolvedParticipantId", right.toString()) },
            { _, _, entry -> entry.put("resolvedParticipantId", direct.toString()) },
            { _, _, entry -> entry.put("slotId", direct.toString()) },
            { _, _, entry -> entry.put("number", 3) },
            { _, _, entry -> entry.remove("resolvedParticipantId") },
        )
        for ((index, change) in changes.withIndex()) {
            val payload = mixed.deepCopy()
            val target = payload["matches"][1]
            val team = target["pointLineups"][0]["teams"][0] as ObjectNode
            change(target, team, team["entries"][0] as ObjectNode)
            assertEquals("INVALID_REQUEST", importPackage(payload, 400, "transfer-invalid-$index")["code"].asText())
            assertEquals(count, transferCounts())
        }
        for (version in 1..3) importPackage(source.deepCopy().put("formatVersion", version), 400)
        assertEquals(count, transferCounts())
        assertEquals(source, export(fixture.id))
    }

    @Test
    fun `portable v4 preserves identification after gameplay undo reopens the point`() {
        val fixture = fixture()
        val slot = UUID.randomUUID()
        val direct = fixture.leftPlayers[0]
        val candidate = fixture.leftPlayers[1]
        val right = fixture.rightPlayers[0]
        val point = confirm(fixture, listOf(temporary(slot), player(direct)), listOf(player(right)))
        event(fixture, "PULL", right, capability = "1")
        event(fixture, "PICKUP", slot, capability = "1")
        val closing = event(fixture, "GOAL", slot, direct, capability = "1")
        response(send("PATCH", slotPath(fixture, point, slot), identification(candidate, 1, closing), login), 200)
        response(send("POST", "${fixture.matchPath}/events/undo", session = login, capability = "1"), 200)
        val source = export(fixture.id)
        assertFalse(source["matches"].single()["events"].any { it["id"].asText() == closing.toString() })
        val result = importPackage(source)
        val copyId = mapped(result, "matchIds", fixture.id)
        val copy = get("/api/v1/matches/$copyId/point-lineups")["points"].single()
        assertEquals("ACTIVE", copy["status"].asText())
        assertEquals(mapped(result, "playerIds", candidate).toString(), resolution(copy))
    }

    @Test
    fun `export refuses a live actor pointing at an orphaned lineup`() {
        val fixture = fixture()
        val pull = event(fixture, "PULL", fixture.rightPlayers[0])
        event(fixture, "PICKUP", fixture.leftPlayers[0])
        event(fixture, "GOAL", fixture.leftPlayers[0], fixture.leftPlayers[1])
        val slot = UUID.randomUUID()
        confirm(fixture, emptyList(), listOf(temporary(slot)))
        response(send("POST", "${fixture.matchPath}/events/undo", session = login, capability = "1"), 200)
        // Simulate persisted history with an invalid point association despite valid foreign keys.
        jdbc.update("UPDATE events SET from_participant_id = NULL, from_slot_id = ? WHERE id = ?", slot, pull)
        assertEquals("POINT_LINEUP_EXPORT_UNSUPPORTED", response(send("POST", "/api/v1/matches/export",
            mapOf("matchIds" to listOf(fixture.id)), login, capture = "transfer-orphan-rejected"), 409)["code"].asText())
    }

    protected fun verifyPortableWriteGates() {
        val ordinary = fixture()
        event(ordinary, "PULL", ordinary.rightPlayers[0])
        val legacy = mutableListOf(export(ordinary.id, "transfer-v1-source"))
        addAnnouncement(ordinary)
        legacy += export(ordinary.id, "transfer-v2-source")
        event(ordinary, "POSSESSION_SET", ordinary.leftPlayers[0])
        legacy += export(ordinary.id, "transfer-v3-source")
        for ((index, file) in legacy.withIndex()) {
            assertEquals(index + 1, file["formatVersion"].asInt())
            assertFalse(file["matches"].single().has("pointLineups"))
            val result = importPackage(file, capture = "transfer-v${index + 1}-import")
            val copy = export(mapped(result, "matchIds", ordinary.id), "transfer-v${index + 1}-copy")
            assertEquals(file["formatVersion"], copy["formatVersion"])
            assertEquals(file["matches"].single()["events"].map { it["type"] }, copy["matches"].single()["events"].map { it["type"] })
        }

        val fixture = fixture()
        val slot = UUID.randomUUID()
        confirm(fixture, listOf(temporary(slot)), listOf(player(fixture.rightPlayers[0])))
        event(fixture, "PULL", fixture.rightPlayers[0], capability = "1")
        val lineupOnly = export(fixture.id)
        addAnnouncement(fixture)
        val withAnnouncement = export(fixture.id)
        event(fixture, "POSSESSION_SET", slot, capability = "1")
        val withPossession = export(fixture.id).also { (it["matches"][0] as ObjectNode).remove("announcements") }

        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val substitution = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", slot, fixture.leftPlayers[0]).at(3)
        response(send("POST", "$journal/batch", batch(2, 2, substitution), login, capability = "2"), 201)
        val v5 = export(fixture.id)
        val v5WithAnnouncement = v5.deepCopy().also { (it["matches"][0]["events"] as ArrayNode).remove(1) }
        val v5WithPossession = v5.deepCopy().also { (it["matches"][0] as ObjectNode).remove("announcements") }

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
            "--ultistats.point-lineups.write-enabled=false", "--ultistats.possession-change.write-enabled=true",
            "--ultistats.announcements.write-enabled=true", "--ultistats.point-substitutions.write-enabled=true",
        ).use { app ->
            val restartedPort = (app as ServletWebServerApplicationContext).webServer.port
            val count = transferCounts()
            assertEquals(v5, export(fixture.id, "transfer-v5-lineup-off-export", restartedPort))
            assertEquals("POINT_LINEUP_WRITE_DISABLED", importPackage(lineupOnly, 409,
                "transfer-off-import", restartedPort)["code"].asText())
            assertEquals("POINT_LINEUP_WRITE_DISABLED", importPackage(v5, 409,
                "transfer-v5-lineup-off", restartedPort)["code"].asText())
            assertEquals(count, transferCounts())
            importPackage(legacy[2], targetPort = restartedPort)
        }

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
            "--ultistats.point-lineups.write-enabled=true", "--ultistats.possession-change.write-enabled=false",
            "--ultistats.announcements.write-enabled=false", "--ultistats.point-substitutions.write-enabled=true",
        ).use { app ->
            val restartedPort = (app as ServletWebServerApplicationContext).webServer.port
            val count = transferCounts()
            for ((label, payload, code) in listOf(
                Triple("v2-announcement-off", legacy[1], "ANNOUNCEMENT_WRITE_DISABLED"),
                Triple("v3-possession-off", legacy[2], "POSSESSION_CHANGE_WRITE_DISABLED"),
                Triple("v4-announcement-off", withAnnouncement, "ANNOUNCEMENT_WRITE_DISABLED"),
                Triple("v4-possession-off", withPossession, "POSSESSION_CHANGE_WRITE_DISABLED"),
                Triple("v5-announcement-off", v5WithAnnouncement, "ANNOUNCEMENT_WRITE_DISABLED"),
                Triple("v5-possession-off", v5WithPossession, "POSSESSION_CHANGE_WRITE_DISABLED"),
            )) {
                assertEquals(code, importPackage(payload, 409, "transfer-$label", restartedPort)["code"].asText())
                assertEquals(count, transferCounts())
            }
            importPackage(lineupOnly, targetPort = restartedPort)
        }
    }

    private fun addAnnouncement(fixture: Fixture): UUID {
        val id = UUID.randomUUID()
        val route = "${fixture.matchPath}/announcements"
        response(send("POST", route, mapOf("id" to id, "teamId" to fixture.teamIds[0],
            "startedAt" to startedAt.plusSeconds(7).plusNanos(123456789)), login), 201)
        response(send("POST", "$route/$id/end", mapOf("endedAt" to startedAt.plusSeconds(8).plusNanos(987654321)), login), 200)
        return id
    }

    private fun export(id: UUID, capture: String? = null, targetPort: Int = port): ObjectNode =
        response(send("POST", "/api/v1/matches/export", mapOf("matchIds" to listOf(id)),
            login, capture = capture, targetPort = targetPort), 200) as ObjectNode

    private fun importPackage(packageBody: JsonNode, status: Int = 200, capture: String? = null, targetPort: Int = port) =
        response(send("POST", "/api/v1/matches/import", packageBody, login, capture = capture, targetPort = targetPort), status)

    private fun mapped(result: JsonNode, key: String, source: UUID): UUID =
        UUID.fromString(result[key][source.toString()].asText())

    private fun transferCounts() = listOf(
        "matches", "teams", "players", "match_teams", "match_participants", "events", "match_announcements",
        "match_point_lineups", "match_point_lineup_entries",
    ).map { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }

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

    @Test
    fun `incoming reserve is committed with its substitution and reused on exact retry`() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val (thrower, receiver) = fixture.leftPlayers
        val reserve = UUID.randomUUID()
        val initial = confirm(fixture, listOf(player(thrower), player(receiver)), listOf(player(fixture.rightPlayers[0])))
        val pull = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", thrower).at(2)
        response(send("POST", "$journal/batch", batch(0, 0, pull, pickup), login, capability = "2", capture = "reserve-ordinary-header2"), 201)
        val replace = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", thrower, reserve).at(5) +
            ("createIncomingReserve" to true)
        val request = batch(2, 1, replace)
        val committed = response(send("POST", "$journal/batch", request, login, capability = "3", capture = "reserve-append"), 201)
        val raw = committed["events"].single()
        assertEquals(reserve.toString(), raw["toParticipantId"].asText())
        assertFalse(raw.has("createIncomingReserve"))
        assertEquals(committed, response(send("POST", "$journal/batch", request, login, capability = "3", capture = "reserve-retry"), 200))
        val discovery = response(send("GET", fixture.path, capture = "reserve-discovery"), 200)
        assertTrue(discovery["incomingReservesWriteEnabled"].asBoolean())
        assertEquals(committed["logRevision"], discovery["logRevision"])
        val point = discovery["points"].single()
        assertEquals(initial["lineup"], point["lineup"])
        val entry = point["effectiveTeams"][0]["entries"][0]
        assertEquals("TEMPORARY", entry["kind"].asText())
        assertEquals(reserve.toString(), entry["slotId"].asText())
        val metadata = point["substitutionSlots"].single()
        assertEquals(1, metadata["number"].asInt())
        assertTrue(metadata["resolvedParticipantId"].isNull)
        assertEquals(fixture.teamIds[0].toString(), metadata["teamId"].asText())
        assertEquals(replace["eventId"].toString(), metadata["introducedByEventId"].asText())
    }

    @Test
    fun `incoming reserve batches reject invalid and stale writes before allocating any slot`() {
        val fixture = fixture(rosterSize = 3)
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val (outgoing, known, unused) = fixture.leftPlayers
        val original = UUID.randomUUID()
        val reserve = UUID.randomUUID()
        val point = confirm(fixture, listOf(player(outgoing), temporary(original, 2)), listOf(player(fixture.rightPlayers[0])))
        val pull = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        val first = reserveEvent(outgoing, reserve, 2)
        fun append(body: Any, status: Int, capture: String? = null, capability: String = "3") =
            response(send("POST", "$journal/batch", body, login, capability = capability, capture = capture), status)

        assertEquals("POINT_SUBSTITUTION_CONFLICT", append(batch(0, 0, first), 409)["code"].asText())
        append(batch(0, 0, pull), 201, "reserve-ordinary-web2-post", "2")
        response(send("GET", journal, capability = "2", capture = "reserve-ordinary-web2-get"), 200)
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", append(batch(1, 1, first), 409, "reserve-new-requires3", "2")["code"].asText())
        val knownReplace = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", outgoing, known).at(1)
        val knownRequest = batch(1, 1, knownReplace)
        val knownResponse = append(knownRequest, 201, "reserve-known-web2-post", "2")
        assertEquals(knownResponse, append(knownRequest, 200, "reserve-known-web3-retry"))
        response(send("GET", journal, capability = "2", capture = "reserve-known-web2-get"), 200)
        val knownPackage = export(fixture.id)
        val knownEvent = knownPackage["matches"][0]["events"].last() as ObjectNode
        knownEvent.put("createIncomingReserve", true)
        assertEquals("INVALID_REQUEST", importPackage(knownPackage, 400, "reserve-v5-import-marker-rejected")["code"].asText())
        knownEvent.remove("createIncomingReserve")
        knownEvent.put("toParticipantId", reserve.toString())
        assertEquals("INVALID_REQUEST", importPackage(knownPackage, 400, "reserve-v5-import-unknown-slot-rejected")["code"].asText())
        val firstAfterKnown = reserveEvent(known, reserve, 2)
        assertEquals("EVENT_LOG_STALE", append(batch(1, 1, firstAfterKnown), 409, "reserve-stale")["code"].asText())
        val priorRows = journalRows(fixture)
        val priorCounts = transferCounts()
        val pass = eventBody(UUID.randomUUID(), "PASS", reserve, original).at(3)
        assertEquals("POINT_SUBSTITUTION_CONFLICT", append(batch(2, 2, firstAfterKnown, reserveEvent(known, UUID.randomUUID(), 3)), 409,
            "reserve-batch-atomic-rejection")["code"].asText())
        for (target in listOf(original, unused)) {
            assertEquals("POINT_SUBSTITUTION_CONFLICT", append(batch(2, 2, reserveEvent(known, target, 2)), 409)["code"].asText())
        }
        for (marker in listOf(false, "true", 1, NullNode.instance)) {
            assertEquals("INVALID_REQUEST", append(batch(2, 2, firstAfterKnown + ("createIncomingReserve" to marker)), 400)["code"].asText())
        }
        assertEquals("INVALID_REQUEST", append(batch(2, 2, pull.withNewId() + ("createIncomingReserve" to true)), 400)["code"].asText())
        assertEquals("INVALID_REQUEST", append(batch(2, 2, pass + ("createIncomingReserve" to true)), 400)["code"].asText())
        response(send("POST", "$journal/batch", batch(2, 2, firstAfterKnown), capability = "3"), 401)
        response(send("POST", "$journal/batch", batch(2, 2, firstAfterKnown), login, csrf = false, capability = "3"), 403)
        assertEquals(priorRows, journalRows(fixture))
        assertEquals(priorCounts, transferCounts())
        assertTrue(get(fixture.path)["points"].single()["substitutionSlots"].isEmpty)

        val pickup = eventBody(UUID.randomUUID(), "PICKUP", reserve).at(3)
        val committed = append(batch(2, 2, firstAfterKnown, pickup, pass), 201, "reserve-batch-with-following-actions")
        assertEquals(3, committed["events"].size())
        assertEquals(3, get(fixture.path)["points"].single()["substitutionSlots"].single()["number"].asInt())
        assertEquals("EVENT_IDEMPOTENCY_CONFLICT",
            append(batch(2, 2, firstAfterKnown - "createIncomingReserve", pickup, pass), 409, "reserve-marker-changed")["code"].asText())

        val oldGet = response(send("GET", journal, capability = "2", capture = "reserve-affected-web2-get"), 409)
        val oldPost = append(batch(5, 3, pass.withNewId()), 409, "reserve-affected-web2-post", "2")
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", oldGet["code"].asText())
        assertEquals(oldGet["code"], oldPost["code"])
        assertEquals(5, oldPost["lastSequenceNumber"].asInt())
        assertEquals(3, oldPost["logRevision"].asInt())
        val v1 = "${fixture.matchPath}/events"
        assertEquals(oldGet["code"], response(send("GET", v1, capture = "reserve-affected-flutter-get"), 409)["code"])
        assertEquals(oldGet["code"], response(send("POST", v1, pass.withNewId(), login, capture = "reserve-affected-flutter-post"), 409)["code"])
        assertEquals(oldGet["code"], response(send("PUT", "${fixture.path}/${point["pointId"].asText()}",
            fixture.selection(3, listOf(player(outgoing), temporary(original, 2)), listOf(player(fixture.rightPlayers[0]))),
            login, capability = "2", capture = "reserve-affected-web2-confirm"), 409)["code"])
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("DELETE", "$v1/${firstAfterKnown["eventId"]}",
            session = login, capability = "3", capture = "reserve-delete-dependent"), 409)["code"].asText())

        for ((index, event) in listOf(pass, pickup, firstAfterKnown).withIndex()) {
            assertEquals("APPLIED", response(send("POST", "$journal/undo", undo(event, 3L + index), login,
                capability = "3", capture = "reserve-ordered-undo-$index"), 200)["outcome"].asText())
        }
        response(send("GET", journal, capability = "2", capture = "reserve-restored-web2-get"), 200)
        assertTrue(get(fixture.path)["points"].single()["substitutionSlots"].isEmpty)
        assertEquals(5, export(fixture.id, "reserve-restored-export")["formatVersion"].asInt())
        assertEquals("POINT_SUBSTITUTION_CONFLICT", append(batch(5, 6, reserveEvent(known, reserve, 6)), 409)["code"].asText())
        append(batch(5, 6, reserveEvent(known, UUID.randomUUID(), 6)), 201)
        assertEquals(4, get(fixture.path)["points"].single()["substitutionSlots"].single()["number"].asInt())
    }

    @Test
    fun `historical incoming reserves retain raw events, possession time and identification after leaving`() {
        val fixture = fixture(rosterSize = 3)
        val (outgoing, receiver, candidate) = fixture.leftPlayers
        val puller = fixture.rightPlayers[0]
        val (reserve, next, rightReserve) = List(3) { UUID.randomUUID() }
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val point = confirm(fixture, listOf(player(outgoing), player(receiver)), listOf(player(puller)))
        val pull = eventBody(UUID.randomUUID(), "PULL", puller)
        val rightReplace = reserveEvent(puller, rightReserve, 1)
        val brick = eventBody(UUID.randomUUID(), "BRICK", puller).at(2)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", outgoing).at(3)
        val replace = reserveEvent(outgoing, reserve, 5)
        val nextReplace = reserveEvent(reserve, next, 8)
        val goal = eventBody(UUID.randomUUID(), "GOAL", next, receiver).at(12)
        response(send("POST", "$journal/batch", batch(0, 0, pull, rightReplace, brick, pickup, replace, nextReplace, goal),
            login, capability = "3", capture = "reserve-successive-holder-and-brick"), 201)
        val public = response(send("GET", fixture.path, capture = "reserve-completed-discovery"), 200)
        val completed = public["points"].first()
        val slots = completed["substitutionSlots"].associateBy { it["slotId"].asText() }
        assertEquals(1, slots.getValue(reserve.toString())["number"].asInt())
        assertEquals(2, slots.getValue(next.toString())["number"].asInt())
        assertEquals(1, slots.getValue(rightReserve.toString())["number"].asInt())
        assertEquals(next.toString(), completed["effectiveTeams"][0]["entries"][0]["slotId"].asText())
        assertTrue(public["points"].last()["substitutionSlots"].isEmpty)
        val path = "${fixture.path}/${point["pointId"].asText()}/slots/$reserve"
        val scoreAndStats = "${fixture.matchPath}/statistics"
        val before = response(send("GET", scoreAndStats, session = login, capture = "reserve-stats-before-identification"), 200)
        assertEquals(9000, before["teams"].first()["time"]["possessionTimeMs"].asInt())
        assertEquals(1, before["teams"].first()["attack"]["score"].asInt())
        assertEquals(2000, participant(before, outgoing)["time"]["possessionTimeMs"].asInt())
        val actors = statistics.recalculateMatchStatistics(matches.getOrThrow(fixture.id)).playerStatistics
            .associateBy { it.participantId }
        assertEquals(Duration.ofSeconds(3), actors.getValue(reserve).time.totalPossessionTime)
        assertEquals(Duration.ofSeconds(4), actors.getValue(next).time.totalPossessionTime)
        assertEquals(0, actors.getValue(reserve).attack.discPossessions)
        assertEquals(0, actors.getValue(next).attack.discPossessions)
        val raw = response(send("GET", journal, capability = "3", capture = "reserve-canonical-raw-log"), 200)
        assertEquals(puller.toString(), raw["events"][2]["participantId"].asText())
        assertEquals(reserve.toString(), raw["events"][5]["fromParticipantId"].asText())
        val closing = goal.getValue("eventId")
        assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("PATCH", path, identification(candidate, 1, closing),
            login, capability = "2", capture = "reserve-identify-web2"), 409)["code"].asText())
        assertConflict(send("PATCH", path, identification(outgoing, 1, closing), login, capability = "3"))
        assertConflict(send("PATCH", path, identification(puller, 1, closing), login, capability = "3"))
        val identified = response(send("PATCH", path, identification(candidate, 1, closing), login, capability = "3",
            capture = "reserve-identify-historical"), 200)
        assertEquals(completed["lineup"]["teams"], identified["lineup"]["teams"])
        assertEquals(candidate.toString(), identified["substitutionSlots"].single {
            it["slotId"].asText() == reserve.toString()
        }["resolvedParticipantId"].asText())
        val nextPath = "${fixture.path}/${point["pointId"].asText()}/slots/$next"
        assertConflict(send("PATCH", nextPath, identification(candidate, 2, closing), login, capability = "3"))
        val after = response(send("GET", scoreAndStats, session = login, capture = "reserve-stats-after-identification"), 200)
        assertEquals(3000, participant(after, candidate)["time"]["possessionTimeMs"].asInt())
        assertEquals(before["teams"].first()["time"], after["teams"].first()["time"])
        assertEquals(before["teams"].first()["attack"], after["teams"].first()["attack"])
        assertEquals(raw, response(send("GET", journal, capability = "3", capture = "reserve-log-after-identification"), 200))
        assertEquals("POINT_LINEUP_EXPORT_UNSUPPORTED", response(send("POST", "/api/v1/matches/export",
            mapOf("matchIds" to listOf(fixture.id)), login, capture = "reserve-export-rejected"), 409)["code"].asText())

        val nextPoint = public["points"].last()["pointId"].asText()
        val nextPointPath = "${fixture.path}/$nextPoint"
        val nextSelection = fixture.selection(1, listOf(player(outgoing)), listOf(player(puller)))
        val duplicateSlot = fixture.selection(1, listOf(temporary(next)), listOf(player(puller)))
        response(send("PUT", nextPointPath, duplicateSlot, login, capability = "3"), 400)
        response(send("PUT", nextPointPath, nextSelection, login, capability = "3"), 200)
        val nextPull = eventBody(UUID.randomUUID(), "PULL", puller).at(14)
        val crossPoint = reserveEvent(next, UUID.randomUUID(), 15)
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("POST", "$journal/batch", batch(7, 1, nextPull, crossPoint),
            login, capability = "3", capture = "reserve-cross-point-rejected"), 409)["code"].asText())
        assertEquals(raw, response(send("GET", journal, capability = "3"), 200))

        val ordinary = fixture()
        val v1 = "${ordinary.matchPath}/events"
        response(send("POST", v1, eventBody(UUID.randomUUID(), "PULL", ordinary.rightPlayers[0]), login,
            capture = "reserve-ordinary-flutter-post"), 201)
        response(send("GET", v1, capture = "reserve-ordinary-flutter-get"), 200)
    }

    private fun reserveEvent(from: UUID, slot: UUID, second: Long) =
        eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", from, slot).at(second) + ("createIncomingReserve" to true)

    @Test
    fun `substitution replaces a temporary holder and protects journal across clients and undo`() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events"
        val v2 = journal.replace("/v1/", "/v2/")
        val slot = UUID.randomUUID()
        val incoming = fixture.leftPlayers[1]
        val initial = confirm(fixture, listOf(temporary(slot), player(fixture.leftPlayers[0])),
            listOf(player(fixture.rightPlayers[0])))["lineup"]["teams"]
        val pull = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", slot).at(2)
        response(send("POST", "$v2/batch", batch(0, 0, pull, pickup), login, capability = "1"), 201)
        val replacement = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", slot, incoming).at(5)
        val body = batch(2, 1, replacement)
        val applied = response(send("POST", "$v2/batch", body, login, capability = "2", capture = "substitution-append"), 201)
        assertEquals(slot.toString(), applied["events"].single()["fromParticipantId"].asText())
        assertEquals(applied, response(send("POST", "$v2/batch", body, login, capability = "2", capture = "substitution-replay"), 200))

        val lineup = response(send("GET", fixture.path, session = login, capture = "substitution-discover-staff"), 200)
        assertTrue(lineup["substitutionsWriteEnabled"].asBoolean())
        val point = lineup["points"].single()
        assertEquals(initial, point["lineup"]["teams"])
        assertEquals(incoming.toString(), point["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        val pass = eventBody(UUID.randomUUID(), "PASS", incoming, fixture.leftPlayers[0]).at(9)
        for ((label, version) in listOf("legacy" to null, "v1-header" to "1")) {
            for ((name, path) in listOf("v1-list" to journal, "v1-detail" to "$journal/${pull["eventId"]}", "v2-log" to v2)) {
                assertEquals("POINT_LINEUP_CLIENT_REQUIRED",
                    response(send("GET", path, capability = version, capture = "substitution-$label-$name"), 409)["code"].asText())
            }
            assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", journal, pass - "eventId", login,
                capability = version, capture = "substitution-$label-v1-post"), 409)["code"].asText())
            assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", "$v2/batch", batch(3, 2, pass), login,
                capability = version, capture = "substitution-$label-v2-batch"), 409)["code"].asText())
            assertEquals("POINT_LINEUP_CLIENT_REQUIRED", response(send("POST", "$v2/undo", undo(replacement, 2), login,
                capability = version, capture = "substitution-$label-v2-undo"), 409)["code"].asText())
        }
        response(send("GET", v2, capability = "2", capture = "substitution-capable-v2-log"), 200)
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("PATCH", "$journal/${replacement["eventId"]}",
            mapOf("type" to "PLAYER_SUBSTITUTION"), login, capability = "2"), 409)["code"].asText())
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("POST", journal, replacement,
            login, capability = "2"), 409)["code"].asText())
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("DELETE", "$journal/${pull["eventId"]}",
            session = login, capability = "2"), 409)["code"].asText())
        response(send("POST", "$v2/batch", batch(3, 2, pass), login, capability = "2"), 201)
        assertEquals("POINT_SUBSTITUTION_CONFLICT", response(send("DELETE", "$journal/${replacement["eventId"]}",
            session = login, capability = "2", capture = "substitution-delete-dependent"), 409)["code"].asText())
        val stats = response(send("GET", "${fixture.matchPath}/statistics", session = login, capture = "substitution-stats"), 200)
        assertEquals(7000, stats["teams"].first()["time"]["possessionTimeMs"].asInt())
        assertEquals(4000, participant(stats, incoming)["time"]["possessionTimeMs"].asInt())
        assertEquals(1, participant(stats, incoming)["attack"]["passes"].asInt())
        assertEquals(0, participant(stats, incoming)["attack"]["discPossessions"].asInt())

        response(send("POST", "$v2/undo", undo(pass, 3), login, capability = "2"), 200)
        response(send("POST", "$v2/undo", undo(replacement, 4), login, capability = "2", capture = "substitution-undo"), 200)
        assertEquals(initial, get(fixture.path)["points"].single()["effectiveTeams"])
        assertEquals(2, response(send("GET", v2, capability = "1"), 200)["events"].size())
    }

    @Test
    fun `substitution validates both teams and preserves pause durations and gameplay counters`() {
        val fixture = fixture()
        val v2 = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val left = fixture.leftPlayers
        val right = fixture.rightPlayers
        val slot = UUID.randomUUID()
        val puller = UUID.randomUUID()
        confirm(fixture, listOf(temporary(slot), player(left[0])), listOf(temporary(puller)))
        fun action(type: String, from: UUID, time: Long, to: UUID? = null) =
            eventBody(UUID.randomUUID(), type, from, to).at(time)
        fun replace(from: UUID, to: UUID, time: Long) = action("PLAYER_SUBSTITUTION", from, time, to)
        fun command(sequence: Int, revision: Long, vararg events: Map<String, Any>, capture: String? = null) =
            send("POST", "$v2/batch", batch(sequence, revision, *events), login, capability = "2", capture = capture)
        fun reject(code: String, result: HttpResponse<String>, status: Int = 409) =
            assertEquals(code, response(result, status)["code"].asText())

        reject("POINT_SUBSTITUTION_CONFLICT", command(0, 0, replace(puller, right[1], 1)))
        response(command(0, 0, action("PULL", puller, 0)), 201)
        reject("EVENT_LOG_STALE", command(0, 0, replace(puller, right[1], 1), capture = "substitution-stale"))
        reject("INVALID_REQUEST", command(1, 1, replace(puller, left[1], 1), capture = "substitution-cross-team"), 400)
        reject("POINT_SUBSTITUTION_CONFLICT", command(1, 1, replace(slot, left[0], 1), capture = "substitution-incoming-on-field"))
        val unknown = matches.getOrThrow(fixture.id).participantsByTeam.getValue(fixture.teamIds[0])
            .first { it.kind == MatchParticipantKind.UNKNOWN }.participantId
        val before = journalRows(fixture)
        reject("EVENT_BEFORE_START", command(1, 1, replace(puller, right[1], -1)))
        reject("POINT_SUBSTITUTION_CONFLICT", command(1, 1, replace(puller, right[1], 1),
            replace(slot, unknown, 1), capture = "substitution-atomic-rejection"))
        assertEquals(before, journalRows(fixture))

        val timeout = fixture.teamIds[0]
        fun pause(type: String, time: Long) =
            mapOf("type" to type, "eventId" to UUID.randomUUID(), "teamId" to timeout).at(time)
        val openAnnouncement = pause("ANNOUNCEMENT_START", 9)
        val closeAnnouncement = pause("ANNOUNCEMENT_END", 11) - "teamId"
        response(command(1, 1,
            replace(puller, right[1], 1), // No holder after PULL.
            action("BRICK", puller, 1), // The brick attributes the original pull, including an exited slot.
            action("PICKUP", left[0], 2),
            replace(right[1], right[0], 3), // Other team holds the disc.
            pause("TIMEOUT_START", 4),
            replace(left[0], left[1], 6), // The timeout stays open while the holder changes.
            pause("TIMEOUT_END", 8),
            openAnnouncement,
            replace(right[0], right[1], 10),
            closeAnnouncement,
            action("GOAL", left[1], 12, slot),
            capture = "substitution-pause-and-response"), 201)
        val stats = response(send("GET", "${fixture.matchPath}/statistics", session = login, capture = "substitution-pause-stats"), 200)
        val leftStats = stats["teams"].first()
        assertEquals(4000, leftStats["time"]["possessionTimeMs"].asInt())
        assertEquals(4000, leftStats["time"]["timeoutTimeMs"].asInt())
        assertEquals(2000, stats["announcements"]["durationMs"].asInt())
        assertEquals(6000, stats["time"]["pureGameTimeMs"].asInt())
        assertEquals(1, stats["teams"][1]["attack"]["bricks"].asInt())
        assertEquals(1, leftStats["attack"]["possessions"].asInt())
        assertEquals(1, leftStats["attack"]["score"].asInt())
        assertEquals(2000, participant(stats, left[0])["time"]["possessionTimeMs"].asInt())
        assertEquals(2000, participant(stats, left[1])["time"]["possessionTimeMs"].asInt())
        assertEquals(1, participant(stats, left[1])["attack"]["assists"].asInt())
        assertEquals(0, participant(stats, left[1])["attack"]["discPossessions"].asInt())

        matches.endMatch(fixture.id, startedAt.plusSeconds(13))
        val finished = response(send("GET", fixture.path, capture = "substitution-discover-public-finished"), 200)
        assertTrue(finished["substitutionsWriteEnabled"].asBoolean())
        val point = finished["points"].single()
        assertEquals("COMPLETED", point["status"].asText())
        assertEquals(left[1].toString(), point["effectiveTeams"][0]["entries"][1]["participantId"].asText())
        assertEquals(right[1].toString(), point["effectiveTeams"][1]["entries"][0]["participantId"].asText())
        val stream = send("GET", "${fixture.matchPath}/events/stream", capture = "substitution-public-sse-finished")
        assertEquals(200, stream.statusCode())
        assertTrue(stream.body().contains("event:connected"))
        assertTrue(stream.body().contains("event:match-finished"))
        assertFalse(stream.body().contains("PLAYER_SUBSTITUTION"))
    }

    @Test
    fun `substituted slot stays identifiable in history without aliasing the replacement`() {
        val fixture = fixture(rosterSize = 3)
        val slot = UUID.randomUUID()
        val left = fixture.leftPlayers
        val point = confirm(fixture, listOf(temporary(slot), player(left[0])), listOf(player(fixture.rightPlayers[0])))
        val right = fixture.rightPlayers
        val pull = eventBody(UUID.randomUUID(), "PULL", right[0])
        val reply = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", right[0], right[1]).at(1)
        val brick = eventBody(UUID.randomUUID(), "BRICK", right[0]).at(1)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", slot).at(2)
        val replacement = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", slot, left[1]).at(5)
        val goal = eventBody(UUID.randomUUID(), "GOAL", left[1], left[0]).at(9)
        val v2 = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        response(send("POST", "$v2/batch", batch(0, 0, pull, reply, brick, pickup, replacement, goal), login, capability = "2"), 201)
        val before = response(send("GET", v2, capability = "2"), 200)
        assertConflict(send("PATCH", slotPath(fixture, point, slot), identification(left[1], 1, goal.getValue("eventId")),
            login, capability = "2", capture = "substitution-identify-incoming-rejected"))
        val identified = response(send("PATCH", slotPath(fixture, point, slot), identification(left[2], 1, goal.getValue("eventId")),
            login, capability = "2", capture = "substitution-identify-previous-slot"), 200)
        assertEquals(left[2].toString(), resolution(identified))
        assertEquals(left[1].toString(), identified["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        val stats = response(send("GET", "${fixture.matchPath}/statistics", session = login, capture = "substitution-identified-stats"), 200)
        assertEquals(3000, participant(stats, left[2])["time"]["possessionTimeMs"].asInt())
        assertEquals(4000, participant(stats, left[1])["time"]["possessionTimeMs"].asInt())
        assertEquals(before, response(send("GET", v2, capability = "2"), 200))

        matches.endMatch(fixture.id, startedAt.plusSeconds(10))
        val legacy = fixture()
        confirm(legacy, listOf(player(legacy.leftPlayers[0])), listOf(player(legacy.rightPlayers[0])))
        val announcement = addAnnouncement(legacy)
        event(legacy, "PULL", legacy.rightPlayers[0], capability = "1")
        event(legacy, "POSSESSION_SET", legacy.leftPlayers[0], capability = "1")
        val legacyFile = export(legacy.id)
        assertEquals(4, legacyFile["formatVersion"].asInt())

        val selected = mapOf("matchIds" to listOf(fixture.id, legacy.id))
        val exported = send("POST", "/api/v1/matches/export", selected, login, capture = "transfer-v5-source")
        val source = response(exported, 200) as ObjectNode
        assertEquals(5, source["formatVersion"].asInt())
        val sourceMatch = source["matches"].first()
        assertEquals(listOf("PULL", "PLAYER_SUBSTITUTION", "BRICK", "PICKUP", "PLAYER_SUBSTITUTION", "GOAL"),
            sourceMatch["events"].map { it["type"].asText() })
        assertEquals(legacyFile["matches"].single(), source["matches"][1])

        val result = importPackage(source, capture = "transfer-v5-import")
        val copyId = mapped(result, "matchIds", fixture.id)
        val copyPath = "/api/v1/matches/$copyId"
        val copy = export(copyId, "transfer-v5-copy")["matches"].single()
        val copyPoint = response(send("GET", "$copyPath/point-lineups", capture = "transfer-v5-copy-lineups"), 200)["points"].single()
        assertEquals("FINISHED", get(copyPath)["status"].asText())
        assertEquals(listOf(1, 0), get(copyPath)["teams"].map { it["teamScore"].asInt() })
        assertEquals("COMPLETED", copyPoint["status"].asText())
        assertNotEquals(point["pointId"], copyPoint["pointId"])
        val newSlot = copyPoint["lineup"]["teams"][0]["entries"][0]["slotId"].asText()
        assertNotEquals(slot.toString(), newSlot)
        val newHolder = mapped(result, "playerIds", left[1])
        val newResolved = mapped(result, "playerIds", left[2])
        val newPuller = mapped(result, "playerIds", right[0])
        val newReply = mapped(result, "playerIds", right[1])
        assertNotEquals(left[1], newHolder)
        assertNotEquals(right[0], newPuller)
        assertNotEquals(right[1], newReply)
        assertEquals(newResolved.toString(), resolution(copyPoint))
        assertEquals(newHolder.toString(), copyPoint["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        assertEquals(newReply.toString(), copyPoint["effectiveTeams"][1]["entries"][0]["participantId"].asText())
        assertEquals(listOf(newPuller.toString(), newPuller.toString(), newPuller.toString(), newSlot, newSlot, newHolder.toString()),
            copy["events"].map { it["fromParticipantId"].asText() })
        assertEquals(newReply.toString(), copy["events"][1]["toParticipantId"].asText())
        assertEquals(newHolder.toString(), copy["events"][4]["toParticipantId"].asText())
        for (key in listOf("sequenceNumber", "occurredAt", "type")) {
            assertEquals(sourceMatch["events"].map { it[key] }, copy["events"].map { it[key] })
        }
        assertEquals(sourceMatch["events"].map { mapped(result, "eventIds", UUID.fromString(it["id"].asText())).toString() },
            copy["events"].map { it["id"].asText() })
        val copyStats = response(send("GET", "$copyPath/statistics", capture = "transfer-v5-copy-stats"), 200)
        assertEquals(stats["teams"][0]["attack"], copyStats["teams"][0]["attack"])
        assertEquals(stats["teams"][0]["time"], copyStats["teams"][0]["time"])
        assertEquals(3000, participant(copyStats, newResolved)["time"]["possessionTimeMs"].asInt())
        assertEquals(4000, participant(copyStats, newHolder)["time"]["possessionTimeMs"].asInt())
        assertEquals(1, participant(copyStats, newHolder)["attack"]["assists"].asInt())

        val legacyCopy = export(mapped(result, "matchIds", legacy.id), "transfer-v5-legacy-copy")
        assertEquals(4, legacyCopy["formatVersion"].asInt())
        val legacyCopyMatch = legacyCopy["matches"].single()
        assertEquals(announcement.toString(), legacyCopyMatch["announcements"].single()["id"].asText())
        assertEquals(listOf("PULL", "POSSESSION_SET"), legacyCopyMatch["events"].map { it["type"].asText() })
        assertEquals(1, legacyCopyMatch["pointLineups"].size())
        assertEquals(exported.body(), send("POST", "/api/v1/matches/export", selected, login).body())

        val counts = transferCounts()
        val mutations: Map<String, (ObjectNode) -> Unit> = mapOf(
            "downgrade" to { it.put("formatVersion", 4) },
            "missing-lineup" to { (it["matches"][0] as ObjectNode).remove("pointLineups") },
            "wrong-boundary" to {
                (it["matches"][0]["pointLineups"][0] as ObjectNode).put("openingEventId", pull.getValue("eventId").toString())
            },
            "before-pull" to {
                val events = it["matches"][0]["events"] as ArrayNode
                events.insert(0, (events.remove(1) as ObjectNode).put("sequenceNumber", 1).put("occurredAt", startedAt.toString()))
                (events[1] as ObjectNode).put("sequenceNumber", 2)
            },
            "absent-outgoing" to { (it["matches"][0]["events"][4] as ObjectNode).put("fromParticipantId", left[2].toString()) },
            "same-actor" to { (it["matches"][0]["events"][4] as ObjectNode).put("toParticipantId", slot.toString()) },
            "cross-team" to { (it["matches"][0]["events"][4] as ObjectNode).put("toParticipantId", right[1].toString()) },
            "unknown" to {
                val unknown = it["matches"][0]["teams"][0]["participants"].first { p -> p["kind"].asText() == "UNKNOWN" }
                (it["matches"][0]["events"][4] as ObjectNode).put("toParticipantId", unknown["participantId"].asText())
            },
            "on-field" to { (it["matches"][0]["events"][4] as ObjectNode).put("toParticipantId", left[0].toString()) },
            "resolution-collision" to {
                (it["matches"][0]["pointLineups"][0]["teams"][0]["entries"][0] as ObjectNode)
                    .put("resolvedParticipantId", left[1].toString())
            },
            "post-substitution-actor" to { (it["matches"][0]["events"][5] as ObjectNode).put("fromParticipantId", slot.toString()) },
        )
        for ((label, mutate) in mutations) {
            val rejected = source.deepCopy().also(mutate)
            assertEquals("INVALID_REQUEST", importPackage(rejected, 400, "transfer-v5-reject-$label")["code"].asText())
            assertEquals(counts, transferCounts(), label)
        }
        assertEquals("INVALID_REQUEST", importPackage(legacyFile.deepCopy().put("formatVersion", 5), 400,
            "transfer-v5-reject-without-substitution")["code"].asText())
        assertEquals(counts, transferCounts())
    }

    @Test
    fun `portable v5 preserves current possession and ordered undo in the copy`() {
        val fixture = fixture(rosterSize = 3)
        val from = fixture.leftPlayers[0]
        val to = fixture.leftPlayers[1]
        val puller = fixture.rightPlayers[0]
        val teammate = fixture.leftPlayers[2]
        confirm(fixture, listOf(player(from), player(teammate)), listOf(player(puller)))
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val exact = startedAt.plusSeconds(5).plusNanos(123456789)
        val replacement = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", from, to) + ("occurredAt" to exact.toString())
        response(send("POST", "$journal/batch", batch(0, 0, eventBody(UUID.randomUUID(), "PULL", puller),
            eventBody(UUID.randomUUID(), "POSSESSION_SET", from).at(2), replacement), login, capability = "2"), 201)

        val source = export(fixture.id, "transfer-v5-active")
        assertEquals(5, source["formatVersion"].asInt())
        val result = importPackage(source, capture = "transfer-v5-active-import")
        val copyId = mapped(result, "matchIds", fixture.id)
        val copyPath = "/api/v1/matches/$copyId"
        val copyJournal = "$copyPath/events".replace("/v1/", "/v2/")
        val copied = response(send("GET", copyJournal, capability = "2", capture = "transfer-v5-active-copy-journal"), 200)
        val newReplacement = mapped(result, "eventIds", replacement.getValue("eventId") as UUID)
        val newFrom = mapped(result, "playerIds", from)
        val newTo = mapped(result, "playerIds", to)
        assertEquals(exact.toString(), copied["events"].last()["occurredAt"].asText())
        assertEquals(newReplacement.toString(), copied["events"].last()["id"].asText())
        val copyPoint = get("$copyPath/point-lineups")["points"].single()
        assertEquals(newTo.toString(), copyPoint["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        val stats = response(send("GET", "$copyPath/statistics", session = login, capture = "transfer-v5-active-copy-stats"), 200)
        assertEquals(3123, participant(stats, newFrom)["time"]["possessionTimeMs"].asInt())
        assertEquals(0, participant(stats, newTo)["attack"]["discPossessions"].asInt())

        val undo = undo(mapOf("eventId" to newReplacement), copied["logRevision"].asLong())
        val undone = response(send("POST", "$copyJournal/undo", undo, login, capability = "2", capture = "transfer-v5-copy-undo"), 200)
        assertEquals("APPLIED", undone["outcome"].asText())
        assertEquals(newFrom.toString(), get("$copyPath/point-lineups")["points"].single()["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        val afterUndo = export(copyId, "transfer-v5-copy-after-undo")
        assertEquals(4, afterUndo["formatVersion"].asInt())
        assertEquals(listOf("PULL", "POSSESSION_SET"), afterUndo["matches"].single()["events"].map { it["type"].asText() })
        val restoredLog = response(send("GET", copyJournal, capability = "2"), 200)
        val pass = eventBody(UUID.randomUUID(), "PASS", newFrom, mapped(result, "playerIds", teammate)).at(9)
        response(send("POST", "$copyJournal/batch", batch(restoredLog["lastSequenceNumber"].asInt(),
            restoredLog["logRevision"].asLong(), pass), login, capability = "2", capture = "transfer-v5-copy-pass-after-undo"), 201)
        val restoredStats = response(send("GET", "$copyPath/statistics", session = login, capture = "transfer-v5-copy-stats-after-undo"), 200)
        assertEquals(7000, participant(restoredStats, newFrom)["time"]["possessionTimeMs"].asInt())
        assertEquals(1, participant(restoredStats, newFrom)["attack"]["passes"].asInt())
        assertEquals(source, export(fixture.id))
    }

    @Test
    fun `OpenAPI v5 example can be imported and exported directly`() {
        val docs = response(send("GET", "/v3/api-docs", session = login, capture = "transfer-v5-openapi"), 200)
        val format = docs["components"]["schemas"]["MatchTransferPackage"]["properties"]["formatVersion"]
        assertEquals(5, format["maximum"].asInt())
        val example = docs["paths"]["/api/v1/matches/import"]["post"]["requestBody"]["content"]["application/json"]["examples"]["pointSubstitution"]["value"]
        assertEquals(5, example["formatVersion"].asInt())
        val id = UUID.fromString(example["matches"].single()["id"].asText())
        val result = importPackage(example, capture = "transfer-v5-openapi-import")
        val copy = export(mapped(result, "matchIds", id), "transfer-v5-openapi-copy")
        assertEquals(5, copy["formatVersion"].asInt())
        assertEquals("PLAYER_SUBSTITUTION", copy["matches"].single()["events"].last()["type"].asText())
    }

    protected fun verifyFreshProcessIncomingReserve() {
        val fixture = fixture(rosterSize = 3)
        val (outgoing, receiver, candidate) = fixture.leftPlayers
        val puller = fixture.rightPlayers[0]
        val slot = UUID.randomUUID()
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val initial = confirm(fixture, listOf(player(outgoing), player(receiver)), listOf(player(puller)))
        val pull = eventBody(UUID.randomUUID(), "PULL", puller)
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", outgoing).at(2)
        val replacement = reserveEvent(outgoing, slot, 5)
        val append = batch(0, 0, pull, pickup, replacement)
        val committed = response(send("POST", "$journal/batch", append, login, capability = "3"), 201)

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
            "--ultistats.point-lineups.write-enabled=true", "--ultistats.point-substitutions.write-enabled=true",
        ).use { app ->
            val target = (app as ServletWebServerApplicationContext).webServer.port
            val public = response(send("GET", fixture.path, targetPort = target, capture = "reserve-off-discovery"), 200)
            assertTrue(public["substitutionsWriteEnabled"].asBoolean())
            assertFalse(public["incomingReservesWriteEnabled"].asBoolean())
            assertEquals(slot.toString(), public["points"].single()["substitutionSlots"].single()["slotId"].asText())
            assertEquals(committed, response(send("GET", journal, targetPort = target, capability = "3", capture = "reserve-off-log"), 200))
            assertEquals(committed, response(send("POST", "$journal/batch", append, login,
                targetPort = target, capability = "3", capture = "reserve-off-retry"), 200))
            val next = reserveEvent(slot, UUID.randomUUID(), 7)
            assertEquals("POINT_SUBSTITUTION_WRITE_DISABLED", response(send("POST", "$journal/batch", batch(3, 1, next), login,
                targetPort = target, capability = "3", capture = "reserve-off-fresh"), 409)["code"].asText())
            val command = undo(replacement, 1)
            val undone = response(send("POST", "$journal/undo", command, login, targetPort = target, capability = "3",
                capture = "reserve-off-undo"), 200)
            assertEquals("APPLIED", undone["outcome"].asText())
            assertEquals(undone, response(send("GET", "$journal/undo/${command["commandId"]}", session = login,
                targetPort = target, capture = "reserve-off-outcome"), 200))
            val restored = response(send("GET", fixture.path, targetPort = target, capture = "reserve-off-restored"), 200)["points"].single()
            assertEquals(initial["effectiveTeams"], restored["effectiveTeams"])
            assertTrue(restored["substitutionSlots"].isEmpty)
            response(send("GET", journal, targetPort = target, capability = "2", capture = "reserve-off-restored-web2"), 200)

            val goal = eventBody(UUID.randomUUID(), "GOAL", outgoing, receiver).at(9)
            response(send("POST", "$journal/batch", batch(3, 2, goal), login, targetPort = target, capability = "3"), 201)
            val path = "${fixture.path}/${initial["pointId"].asText()}/slots/$slot"
            assertConflict(send("PATCH", path, identification(candidate, 1, goal.getValue("eventId")), login,
                targetPort = target, capability = "3", capture = "reserve-undone-identification-rejected"))
        }
    }

    protected fun verifyFreshProcessSubstitution() {
        val fixture = fixture()
        val journal = "${fixture.matchPath}/events".replace("/v1/", "/v2/")
        val from = fixture.leftPlayers[0]
        val to = fixture.leftPlayers[1]
        confirm(fixture, listOf(player(from)), listOf(player(fixture.rightPlayers[0])))
        val pull = eventBody(UUID.randomUUID(), "PULL", fixture.rightPlayers[0])
        val pickup = eventBody(UUID.randomUUID(), "PICKUP", from).at(2)
        val replacement = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", from, to).at(5)
        val append = batch(0, 0, pull, pickup, replacement)
        val committed = response(send("POST", "$journal/batch", append, login, capability = "2"), 201)
        val source = export(fixture.id)
        val reverse = eventBody(UUID.randomUUID(), "PLAYER_SUBSTITUTION", to, from).at(6)

        SpringApplicationBuilder(UltistatsApplication::class.java).profiles("postgres-list").run(
            "--server.port=0", "--app.auth.ingress.loopback-mode=true", "--app.auth.ingress.private-port=0",
        ).use { app ->
            val target = (app as ServletWebServerApplicationContext).webServer.port
            val counts = transferCounts()
            assertEquals(source, export(fixture.id, "transfer-v5-off-export", target))
            assertEquals("POINT_SUBSTITUTION_WRITE_DISABLED", importPackage(source, 409, "transfer-v5-off-import", target)["code"].asText())
            assertEquals(counts, transferCounts())
            val snapshot = response(send("GET", fixture.path, targetPort = target, capture = "substitution-discover-default-off"), 200)
            assertFalse(snapshot["writesEnabled"].asBoolean())
            assertFalse(snapshot["substitutionsWriteEnabled"].asBoolean())
            assertEquals(to.toString(), snapshot["points"].single()["effectiveTeams"][0]["entries"][0]["participantId"].asText())
            assertEquals(committed, response(send("GET", journal, targetPort = target,
                capability = "2", capture = "substitution-default-off-log"), 200))
            assertEquals(committed, response(send("POST", "$journal/batch", append, login, targetPort = target,
                capability = "2", capture = "substitution-default-off-replay"), 200))
            assertEquals("POINT_SUBSTITUTION_WRITE_DISABLED", response(send("POST", "$journal/batch", batch(3, 1, reverse), login,
                targetPort = target, capability = "2", capture = "substitution-default-off-fresh"), 409)["code"].asText())
            val undo = undo(replacement, 1)
            val result = response(send("POST", "$journal/undo", undo, login, targetPort = target, capability = "2",
                capture = "substitution-default-off-undo"), 200)
            assertEquals("APPLIED", result["outcome"].asText())
            assertEquals(result, response(send("GET", "$journal/undo/${undo["commandId"]}", session = login, targetPort = target), 200))
            val restored = response(send("GET", fixture.path, targetPort = target, capture = "substitution-default-off-restored"), 200)
            assertEquals(from.toString(), restored["points"].single()["effectiveTeams"][0]["entries"][0]["participantId"].asText())
        }
    }

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

    private fun fixture(start: Boolean = true, rosterSize: Int = 2): Fixture {
        val teamIds = List(2) { UUID.randomUUID() }.onEach { teams.create(Team(it, "Point lineup team")) }
        val roster = teamIds.map { team ->
            List(rosterSize) { UUID.randomUUID() }.onEach {
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
    fun `fresh reserve-off successor reads replays and undoes an incoming reserve`() = verifyFreshProcessIncomingReserve()

    @Test
    fun `default-off successor preserves substitutions and permits replay and ordered undo`() = verifyFreshProcessSubstitution()


    @Test
    fun `portable versions and independent write gates work against migrated PostgreSQL`() = verifyPortableWriteGates()

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
