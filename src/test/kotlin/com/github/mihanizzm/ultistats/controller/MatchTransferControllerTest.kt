package com.github.mihanizzm.ultistats.controller

import org.springframework.context.annotation.Import
import com.github.mihanizzm.ultistats.fixture.LegacyDomainHttpSecurity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.mihanizzm.ultistats.fixture.MatchTransferFixtureHelper
import com.github.mihanizzm.ultistats.model.*
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.service.*
import com.github.mihanizzm.ultistats.transfer.MatchTransferExamples
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.Mockito.doAnswer
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(LegacyDomainHttpSecurity::class, MatchTransferFixtureHelper::class)
class MatchTransferControllerTest {
    @Autowired lateinit var fixtures: MatchTransferFixtureHelper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @MockitoSpyBean lateinit var matches: MatchService
    @Autowired lateinit var teams: TeamService
    @Autowired lateinit var players: PlayerService
    @Autowired lateinit var rosters: TeamPlayerService

    private lateinit var home: Team
    private lateinit var away: Team
    private lateinit var passer: Player
    private lateinit var catcher: Player
    private lateinit var match: Match
    private val start = Instant.parse("2026-10-01T10:00:00Z")

    @BeforeEach
    fun setup() {
        fixtures.clear()
        home = Team(UUID.randomUUID(), "Историческая команда", "Новосибирск", "/uploads/home.png")
        away = Team(UUID.randomUUID(), "Гости")
        passer = Player(UUID.randomUUID(), "Иван", "Первый", "/uploads/player.png")
        catcher = Player(UUID.randomUUID(), "Анна", "Вторая")
        fixtures.saveTeam(home); fixtures.saveTeam(away)
        fixtures.savePlayer(passer); fixtures.savePlayer(catcher)
        fixtures.addMembership(home.id, passer.id, 7); fixtures.addMembership(home.id, catcher.id, 8)
        match = Match(UUID.randomUUID(), listOf(home.id, away.id), plannedStartTimestamp = start, playersPerTeam = 5)
        fixtures.createMatch(match)
    }

    @Test
    fun `portable round trip retains original nanoseconds after database reload`() {
        fixtures.start(match.id, start)
        fixtures.addEvent(OnePlayerEvent(passer.id, Instant.parse("2026-10-01T10:00:01.123456789Z"), EventType.PULL), match.id)
        val source = export(match.id)
        assertThat(source["matches"][0]["events"][0]["occurredAt"].asText())
            .isEqualTo("2026-10-01T10:00:01.123456789Z")
        val result = import(source)
        val copyId = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        assertThat(export(copyId)["matches"][0]["events"][0]["occurredAt"].asText())
            .isEqualTo("2026-10-01T10:00:01.123456789Z")
    }

    @Test
    fun `default writer setting protects marker transfer without affecting legacy package export`() {
        fixtures.start(match.id, start)
        fun request(capability: String? = null) = post("/api/v1/matches/export")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"matchIds":["${match.id}"]}""").apply {
                capability?.let { header(POINT_LINEUPS_HEADER, it) }
            }
        val legacy = mvc.perform(request()).andExpect(status().isOk).andReturn().response.contentAsString
        mvc.perform(request("4")).andExpect(status().isOk).andExpect(content().string(legacy))

        val registered = Instant.parse("2026-10-01T10:00:01.123456789Z")
        fixtures.addEvent(TeamEvent(home.id, registered, EventType.POINT_RESULT), match.id)
        for (capability in listOf(null, "1", "2", "3", "5", "4,4")) {
            mvc.perform(request(capability)).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("POINT_RESULT_CLIENT_REQUIRED"))
        }
        val portable = mvc.perform(request("4")).andExpect(status().isOk)
            .andExpect { assertThat(it.response.getHeaders("Vary")).contains(POINT_LINEUPS_HEADER) }
            .andExpect(jsonPath("$.formatVersion").value(7))
            .andExpect(jsonPath("$.matches[0].events[0].occurredAt").value(registered.toString()))
            .andReturn().response.contentAsString

        val before = Triple(matches.count(), teams.count(), players.count())
        for ((capability, code) in listOf("3" to "POINT_RESULT_CLIENT_REQUIRED", "4" to "POINT_RESULT_WRITE_DISABLED")) {
            mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON)
                .header(POINT_LINEUPS_HEADER, capability).content(portable))
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value(code))
        }
        assertThat(Triple(matches.count(), teams.count(), players.count())).isEqualTo(before)
    }

    @Test
    fun `approved transfer requires explicit v8 opt in and the default off writer blocks import`() {
        fixtures.start(match.id, start)
        fixtures.addEvent(TeamEvent(home.id, start.plusSeconds(1), EventType.POINT_RESULT), match.id)
        fixtures.end(match.id, start.plusSeconds(2))
        val (point) = PointTimeline.from(match.id, MatchStatus.FINISHED, listOf(
            StoredEvent(jdbc.queryForObject("SELECT id FROM events WHERE match_id = ?", UUID::class.java, match.id)!!,
                1, TeamEvent(home.id, start.plusSeconds(1), EventType.POINT_RESULT)),
        ))
        fun request(version: String = "", capability: String = "4") = post("/api/v1/matches/export")
            .contentType(MediaType.APPLICATION_JSON).header(POINT_LINEUPS_HEADER, capability)
            .content("""{"matchIds":["${match.id}"]$version}""")
        val draftAction = UUID.randomUUID()
        jdbc.update("""INSERT INTO match_point_completion_drafts
            (match_id, point_id, closing_event_id, revision, base_log_revision, insertions)
            VALUES (?, ?, ?, 1, 0, ?)""", match.id, point.pointId, point.closingEventId,
            """[{"id":"$draftAction","afterEventId":null,"type":"PULL",
                "fromActorId":"${passer.id}","toActorId":null,"elapsedOffsetMs":null}]""")
        val legacy = mvc.perform(request()).andExpect(jsonPath("$.formatVersion").value(7))
            .andReturn().response.contentAsString
        mvc.perform(request(""","acceptFormatVersion":8""")).andExpect(content().string(legacy))

        val actionId = UUID.randomUUID()
        jdbc.update("""INSERT INTO match_point_completion_approvals
            (match_id, point_id, closing_event_id, insertions) VALUES (?, ?, ?, ?)""",
            match.id, point.pointId, point.closingEventId, """[{"id":"$actionId","afterEventId":null,
                "type":"GOAL","fromActorId":"${passer.id}","toActorId":"${catcher.id}","elapsedOffsetMs":0}]""")
        mvc.perform(request()).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("POINT_COMPLETION_TRANSFER_CLIENT_REQUIRED"))
        mvc.perform(request(""","acceptFormatVersion":"8"""")).andExpect(status().isBadRequest)
        mvc.perform(request(""","acceptFormatVersion":8""", "3")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("POINT_RESULT_CLIENT_REQUIRED"))
        val portable = mvc.perform(request(""","acceptFormatVersion":8""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.formatVersion").value(8))
            .andExpect(jsonPath("$.matches[0].approvedCompletions[0].insertions[0].id").value(actionId.toString()))
            .andReturn().response.contentAsString
        assertThat(portable).doesNotContain(draftAction.toString())
        val before = Triple(matches.count(), teams.count(), players.count())
        fun importRequest(body: String) = post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON)
            .header(POINT_LINEUPS_HEADER, "4").content(body)
        mvc.perform(importRequest(portable))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("POINT_COMPLETION_TRANSFER_CLIENT_REQUIRED"))
        mvc.perform(importRequest(portable).param("acceptFormatVersion", "8"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("POINT_COMPLETION_TRANSFER_IMPORT_DISABLED"))
        mvc.perform(importRequest((mapper.readTree(portable) as ObjectNode).put("formatVersion", 7).toString()))
            .andExpect(status().isBadRequest)
        val mismatched = mapper.readTree(portable)
        (mismatched["matches"][0]["events"][0] as ObjectNode).put("teamId", away.id.toString())
        mvc.perform(importRequest(mismatched.toString())).andExpect(status().isBadRequest)
        assertThat(Triple(matches.count(), teams.count(), players.count())).isEqualTo(before)
    }

    @Test
    fun `portable marker requires a matching format and only a scoring match team`() {
        val before = Triple(matches.count(), teams.count(), players.count())
        val valid = mapper.readTree(MatchTransferExamples.POINT_RESULT_PACKAGE) as ObjectNode
        fun verifyInvalid(source: ObjectNode) {
            mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON)
                .header(POINT_LINEUPS_HEADER, "4").content(source.toString()))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(Triple(matches.count(), teams.count(), players.count())).isEqualTo(before)
        }
        for (version in 1..6) verifyInvalid(valid.deepCopy().put("formatVersion", version))
        verifyInvalid((mapper.readTree(MatchTransferExamples.PACKAGE) as ObjectNode).put("formatVersion", 7))
        for ((field, value) in listOf(
            "teamId" to UUID.randomUUID().toString(),
            "teamId" to null,
            "fromParticipantId" to valid["matches"][0]["teams"][0]["participants"][0]["participantId"].asText(),
            "toParticipantId" to valid["matches"][0]["teams"][1]["participants"][0]["participantId"].asText(),
            "occurredAt" to "2026-10-01T09:59:59Z",
        )) {
            val invalid = valid.deepCopy()
            (invalid["matches"][0]["events"][0] as ObjectNode).put(field, value)
            verifyInvalid(invalid)
        }
    }

    @Test
    fun `round trip preserves finished history and statistics with fresh identities`() {
        finish(match.id)
        fixtures.saveTeam(home.copy(name = "Current name"))
        fixtures.savePlayer(passer.copy(firstName = "Current first name"))
        val unrelated = Player(UUID.randomUUID(), "Extra", "Roster")
        fixtures.savePlayer(unrelated); fixtures.addMembership(home.id, unrelated.id, 9)
        val source = export(match.id)
        assertThat(source["formatVersion"].asInt()).isEqualTo(1)
        assertThat(source["players"].size()).isEqualTo(2)
        assertThat(source.toString()).doesNotContain("photoUrl", "deletedAt", "creationFingerprint", unrelated.id.toString())
        val result = import(source)
        val newId = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        assertThat(newId).isNotEqualTo(match.id)
        val copy = matches.getOrThrow(newId)
        assertThat(copy.status).isEqualTo(MatchStatus.FINISHED)
        assertThat(copy.playersPerTeam).isEqualTo(5)
        assertThat(copy.startedAt).isEqualTo(start)
        assertThat(copy.endedAt).isEqualTo(start.plusSeconds(5))
        assertThat(copy.teamNamesById.values).containsExactly("Историческая команда", "Гости")
        assertThat(copy.participantsByTeam.values.flatten().map { it.displayName }).contains("Иван Первый")
        val copyHome = UUID.fromString(result["teamIds"][home.id.toString()].asText())
        val copyPasser = UUID.fromString(result["playerIds"][passer.id.toString()].asText())
        assertThat(requireNotNull(teams.get(copyHome)).name).isEqualTo("Current name")
        assertThat(requireNotNull(teams.get(copyHome)).photoUrl).isNull()
        assertThat(requireNotNull(players.get(copyPasser)).photoUrl).isNull()
        assertThat(rosters.getByTeamId(copyHome)).isEmpty()
        val oldStats = statistics(match.id)
        val newStats = statistics(newId)
        assertThat(newStats["time"]).isEqualTo(oldStats["time"])
        for (index in 0..1) {
            assertThat(newStats["teams"][index]["attack"]).isEqualTo(oldStats["teams"][index]["attack"])
            assertThat(newStats["teams"][index]["defense"]).isEqualTo(oldStats["teams"][index]["defense"])
        }
        assertThat(result["eventIds"].size()).isEqualTo(3)
        assertThat(matches.getOrThrow(match.id).teamIds).containsExactly(home.id, away.id)
        assertThat(import(source)["matchIds"][match.id.toString()]).isNotEqualTo(result["matchIds"][match.id.toString()])
    }

    @Test
    fun `shared catalog identities survive transfer while all statuses remain usable`() {
        val planned = Match(UUID.randomUUID(), match.teamIds)
        val live = Match(UUID.randomUUID(), match.teamIds)
        fixtures.createMatch(planned); fixtures.createMatch(live)
        fixtures.start(live.id, start)
        val unknown = matches.getOrThrow(live.id).participantsByTeam.getValue(away.id).first()
        fixtures.addEvent(OnePlayerEvent(unknown.participantId, start.plusSeconds(1), EventType.PULL), live.id)
        finish(match.id)
        val source = export(planned.id, live.id, match.id)
        val result = import(source)
        assertThat(result["teamIds"].size()).isEqualTo(2)
        assertThat(result["playerIds"].size()).isEqualTo(2)
        val newPlanned = UUID.fromString(result["matchIds"][planned.id.toString()].asText())
        val newLive = UUID.fromString(result["matchIds"][live.id.toString()].asText())
        val newFinished = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        assertThat(matches.getOrThrow(newPlanned).status).isEqualTo(MatchStatus.PLANNED)
        assertThat(matches.getOrThrow(newLive).status).isEqualTo(MatchStatus.IN_PROGRESS)
        assertThat(matches.getOrThrow(newFinished).status).isEqualTo(MatchStatus.FINISHED)
        assertThat(matches.getOrThrow(newPlanned).teamIds).isEqualTo(matches.getOrThrow(newLive).teamIds)
        mvc.perform(post("/api/v1/matches/$newPlanned/start").contentType(MediaType.APPLICATION_JSON)
            .content("{\"timestamp\":\"$start\"}")).andExpect(status().isOk)
        val newPasser = UUID.fromString(result["playerIds"][passer.id.toString()].asText())
        mvc.perform(post("/api/v1/matches/$newLive/events").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("type" to "PICKUP", "occurredAt" to start.plusSeconds(2), "participantId" to newPasser))))
            .andExpect(status().isCreated)
        assertThat(matches.getOrThrow(newLive).events).hasSize(2)
        assertThat(matches.getOrThrow(newLive).events.first()).isInstanceOf(OnePlayerEvent::class.java)
        val copiedPull = matches.getOrThrow(newLive).events.first() as OnePlayerEvent
        assertThat(copiedPull.participant).isNotEqualTo(unknown.participantId)
        assertThat(matches.getOrThrow(newLive).participantsByTeam.values.flatten().map { it.participantId })
            .contains(copiedPull.participant)
    }

    @Test
    fun `invalid version and dangling references reject whole package before writing`() {
        val source = export(match.id)
        val baseline = matches.count()
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.put("formatVersion", 2) },
            { it.remove("formatVersion") },
            { it.withArray("teams").removeAll() },
            { it.withArray("players").removeAll() },
            { it.withArray("matches").add(it["matches"][0].deepCopy<JsonNode>()) },
            { (it["matches"][0] as ObjectNode).put("playersPerTeam", 1) },
            { (it["matches"][0] as ObjectNode).put("endedAt", start.toString()) },
            { (it["matches"][0]["teams"][0]["participants"][0] as ObjectNode).put("number", -1) },
        )
        mutations.forEach { mutate ->
            val invalid = source.deepCopy()
            mutate(invalid)
            mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            assertThat(matches.count()).isEqualTo(baseline)
        }
    }

    @Test
    fun `export rejects absent deleted duplicate and empty selections`() {
        mvc.perform(post("/api/v1/matches/export").contentType(MediaType.APPLICATION_JSON).content("{\"matchIds\":[]}"))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/matches/export").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("matchIds" to listOf(match.id, match.id)))))
            .andExpect(status().isBadRequest)
        fixtures.deleteMatch(match.id)
        listOf(match.id, UUID.randomUUID()).forEach { id ->
            mvc.perform(post("/api/v1/matches/export").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(mapOf("matchIds" to listOf(id)))))
                .andExpect(status().isNotFound)
        }
        mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `invalid event graphs and timestamps reject package without catalog writes`() {
        finish(match.id)
        val source = export(match.id)
        val baseline = Triple(matches.count(), teams.count(), players.count())
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { (it["matches"][0]["events"][0] as ObjectNode).put("fromParticipantId", UUID.randomUUID().toString()) },
            { (it["matches"][0]["events"][0] as ObjectNode).put("teamId", home.id.toString()) },
            { (it["matches"][0]["events"][0] as ObjectNode).put("type", "PICKUP") },
            { (it["matches"][0]["events"][1] as ObjectNode).put("sequenceNumber", 1) },
            { (it["matches"][0]["events"][1] as ObjectNode).put("occurredAt", start.minusSeconds(1).toString()) },
            { (it["matches"][0]["events"][2] as ObjectNode).put("occurredAt", start.plusSeconds(100).toString()) },
            { (it["matches"][0]["events"][2] as ObjectNode).put("toParticipantId", passer.id.toString()) },
            { (it["matches"][0]["events"][2] as ObjectNode).put("type", "BLOCK") },
            { (it["matches"][0]["events"][2] as ObjectNode).put("id", it["matches"][0]["events"][0]["id"].asText()) },
            { (it["matches"][0]["teams"][0]["participants"] as com.fasterxml.jackson.databind.node.ArrayNode).removeAll() },
            { it.put("unexpected", "field") },
            { it.put("formatVersion", 1.5) },
            { it.put("formatVersion", "1") },
            { it.putNull("formatVersion") },
            { it.withArray("matches").addNull() },
            { it.withArray("players").addNull() },
            { (it["matches"][0]["events"] as com.fasterxml.jackson.databind.node.ArrayNode).addNull() },
        )
        mutations.forEach { mutate ->
            val invalid = source.deepCopy()
            mutate(invalid)
            mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.detail").isNotEmpty)
            assertThat(Triple(matches.count(), teams.count(), players.count())).isEqualTo(baseline)
        }
    }

    @Test
    fun `failure after persisting second match rolls back all matches and catalog cards`() {
        val second = Match(UUID.randomUUID(), match.teamIds)
        fixtures.createMatch(second)
        val source = export(match.id, second.id)
        val baseline = Triple(matches.count(), teams.count(), players.count())
        val calls = AtomicInteger()
        doAnswer { invocation ->
            invocation.callRealMethod()
            if (calls.incrementAndGet() == 2) throw IllegalStateException("Simulated failure after second match write")
            null
        }.`when`(matches).recalculateScore(org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID(0, 0))
        assertThatThrownBy {
            mvc.perform(post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON).content(source.toString()))
        }.hasRootCauseMessage("Simulated failure after second match write")
        assertThat(Triple(matches.count(), teams.count(), players.count())).isEqualTo(baseline)
    }

    @Test
    fun `Swagger package example is directly importable and endpoint schemas are typed`() {
        val docs = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        val operation = docs["paths"]["/api/v1/matches/import"]["post"]
        val content = operation["requestBody"]["content"]["application/json"]
        assertThat(content["schema"]["$" + "ref"].asText()).isEqualTo("#/components/schemas/MatchTransferPackage")
        val schemas = docs["components"]["schemas"]
        val portableMatch = schemas["PortableMatch"]
        assertThat(portableMatch["required"].map { it.asText() }).doesNotContain("announcements")
        assertThat(portableMatch["properties"]["announcements"]["items"]["$" + "ref"].asText())
            .isEqualTo("#/components/schemas/PortableAnnouncement")
        val interval = schemas["PortableAnnouncement"]["properties"]
        assertThat(interval["teamId"]["type"].map { it.asText() }).containsExactlyInAnyOrder("string", "null")
        assertThat(interval["endedAt"]["type"].map { it.asText() }).containsExactlyInAnyOrder("string", "null")
        val example = content["examples"]["portableMatch"]["value"]
        assertThat(example).isNotNull()
        assertThat(import(example)["matchIds"].size()).isEqualTo(1)
        assertThat(content["examples"]["pointResult"]["value"]["formatVersion"].asInt()).isEqualTo(7)
        assertThat(operation["parameters"].map { it["name"].asText() }).contains(POINT_LINEUPS_HEADER)
        val responseSchema = operation["responses"]["200"]["content"]["application/json"]["schema"]
        assertThat(responseSchema["$" + "ref"].asText()).isEqualTo("#/components/schemas/MatchTransferResult")
    }

    @Test
    fun `missing player cards use first snapshot and deleted cards become active independent copies`() {
        fixtures.savePlayer(passer.copy(firstName = "Позднее имя"))
        val later = Match(UUID.randomUUID(), match.teamIds)
        fixtures.createMatch(later)
        fixtures.deleteTeam(home.id)
        fixtures.deletePlayer(catcher.id)
        fixtures.removeCatalogPlayer(passer.id)
        val source = export(later.id, match.id)
        assertThat(source["players"].map { it["firstName"].asText() }).contains("Позднее имя", "Анна")
        val result = import(source)
        val newPasser = UUID.fromString(result["playerIds"][passer.id.toString()].asText())
        val newCatcher = UUID.fromString(result["playerIds"][catcher.id.toString()].asText())
        val newHome = UUID.fromString(result["teamIds"][home.id.toString()].asText())
        assertThat(requireNotNull(players.get(newPasser)).firstName).isEqualTo("Позднее имя")
        val copiedOriginal = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        assertThat(matches.getOrThrow(copiedOriginal).participantsByTeam.values.flatten().map { it.firstName }).contains("Иван")
        assertThat(players.get(newCatcher)).isNotNull()
        assertThat(teams.get(newHome)).isNotNull()
        assertThat(teams.get(home.id)).isNull()
    }

    @Test
    fun `deleted events are omitted and active sequence gaps survive import`() {
        fixtures.start(match.id, start)
        fixtures.addEvent(OnePlayerEvent(passer.id, start.plusSeconds(1), EventType.PULL), match.id)
        fixtures.addEvent(OnePlayerEvent(passer.id, start.plusSeconds(2), EventType.PICKUP), match.id)
        val before = export(match.id)
        val removed = UUID.fromString(before["matches"][0]["events"][1]["id"].asText())
        fixtures.removeEvent(removed, match.id)
        fixtures.addEvent(OnePlayerEvent(catcher.id, start.plusSeconds(3), EventType.PICKUP), match.id)
        val source = export(match.id)
        assertThat(source["matches"][0]["events"].map { it["sequenceNumber"].asInt() }).containsExactly(1, 3)
        assertThat(source.toString()).doesNotContain(removed.toString())
        val result = import(source)
        val newMatch = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        assertThat(export(newMatch)["matches"][0]["events"].map { it["sequenceNumber"].asInt() }).containsExactly(1, 3)
    }

    @Test
    fun `all event categories and types round trip with remapped links`() {
        fixtures.start(match.id, start)
        val opponent = matches.getOrThrow(match.id).participantsByTeam.getValue(away.id).first().participantId
        val log = listOf<Event>(
            OnePlayerEvent(passer.id, start.plusSeconds(1), EventType.PULL),
            OnePlayerEvent(passer.id, start.plusSeconds(2), EventType.BRICK),
            OnePlayerEvent(catcher.id, start.plusSeconds(3), EventType.PICKUP),
            TwoPlayerEvent(catcher.id, passer.id, start.plusSeconds(4), EventType.PASS),
            TwoPlayerEvent(passer.id, opponent, start.plusSeconds(5), EventType.BLOCK),
            OnePlayerEvent(passer.id, start.plusSeconds(6), EventType.PICKUP),
            TwoPlayerEvent(passer.id, opponent, start.plusSeconds(7), EventType.BLOCK_MARKER),
            OnePlayerEvent(passer.id, start.plusSeconds(8), EventType.PICKUP),
            TwoPlayerEvent(passer.id, opponent, start.plusSeconds(9), EventType.BLOCK_FIELD),
            OnePlayerEvent(passer.id, start.plusSeconds(10), EventType.PICKUP),
            TwoPlayerEvent(passer.id, opponent, start.plusSeconds(11), EventType.INTERCEPTION),
            OnePlayerEvent(opponent, start.plusSeconds(12), EventType.INCOMPLETE_PASS),
            OnePlayerEvent(passer.id, start.plusSeconds(13), EventType.PICKUP),
            TeamEvent(home.id, start.plusSeconds(14), EventType.TIMEOUT_START),
            TeamEvent(home.id, start.plusSeconds(15), EventType.TIMEOUT_END),
            TwoPlayerEvent(passer.id, catcher.id, start.plusSeconds(16), EventType.GOAL),
            SystemEvent(start.plusSeconds(17), EventType.HALFTIME_START),
            SystemEvent(start.plusSeconds(18), EventType.HALFTIME_END),
            OnePlayerEvent(opponent, start.plusSeconds(19), EventType.PULL),
            OnePlayerEvent(opponent, start.plusSeconds(20), EventType.PICKUP),
            TwoPlayerEvent(opponent, passer.id, start.plusSeconds(21), EventType.CALLAHAN),
        )
        log.forEach { event ->
            fixtures.addEvent(event, match.id)
        }
        fixtures.end(match.id, start.plusSeconds(22))
        val source = export(match.id)
        val result = import(source)
        val newMatch = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        val copy = export(newMatch)["matches"][0]["events"]
        assertThat(copy.map { it["type"].asText() }).containsExactlyElementsOf(log.map { it.type.name })
        assertThat(copy.map { it["occurredAt"].asText() }).containsExactlyElementsOf(log.map { it.occurredAt.toString() })
        val newTeam = result["teamIds"][home.id.toString()].asText()
        assertThat(copy[13]["teamId"].asText()).isEqualTo(newTeam)
        assertThat(copy[16].has("fromParticipantId")).isFalse()
        assertThat(statistics(newMatch)["teams"][0]["attack"]["score"].asInt()).isEqualTo(2)
    }

    @Test
    fun `later match snapshots add catalog players without importing unrelated roster members`() {
        val laterPlayer = Player(UUID.randomUUID(), "Поздний", "Участник")
        fixtures.savePlayer(laterPlayer)
        fixtures.addMembership(home.id, laterPlayer.id, 11)
        val later = Match(UUID.randomUUID(), match.teamIds)
        fixtures.createMatch(later)
        val extra = Player(UUID.randomUUID(), "Только", "Справочник")
        fixtures.savePlayer(extra)
        fixtures.addMembership(home.id, extra.id, 12)
        val source = export(match.id, later.id)
        assertThat(source["players"].map { it["id"].asText() }).contains(laterPlayer.id.toString()).doesNotContain(extra.id.toString())
        val result = import(source)
        val earlierCopy = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        val laterCopy = UUID.fromString(result["matchIds"][later.id.toString()].asText())
        val participant = UUID.fromString(result["playerIds"][laterPlayer.id.toString()].asText())
        assertThat(matches.getOrThrow(earlierCopy).participantsByTeam.values.flatten().map { it.participantId }).doesNotContain(participant)
        assertThat(matches.getOrThrow(laterCopy).participantsByTeam.values.flatten().map { it.participantId }).contains(participant)
    }

    @Test
    fun `package restores a finished match into an empty destination database`() {
        finish(match.id)
        val source = export(match.id)
        fixtures.clear()
        val result = import(source)
        val newMatch = UUID.fromString(result["matchIds"][match.id.toString()].asText())
        mvc.perform(get("/api/v1/matches/$newMatch"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FINISHED"))
        val stats = statistics(newMatch)
        assertThat(stats["teams"][0]["attack"]["score"].asInt()).isEqualTo(1)
        assertThat(stats["teams"][0]["participants"].map { it["displayName"].asText() }).contains("Иван Первый", "Анна Вторая")
        assertThat(matches.count()).isEqualTo(1)
        assertThat(teams.count()).isEqualTo(2)
        assertThat(players.count()).isEqualTo(2)
    }

    private fun finish(id: UUID) {
        fixtures.start(id, start)
        fixtures.addEvent(OnePlayerEvent(passer.id, start.plusSeconds(1), EventType.PULL), id)
        fixtures.addEvent(OnePlayerEvent(passer.id, start.plusSeconds(2), EventType.PICKUP), id)
        fixtures.addEvent(TwoPlayerEvent(passer.id, catcher.id, start.plusSeconds(3), EventType.GOAL), id)
        fixtures.end(id, start.plusSeconds(5))
    }

    private fun export(vararg ids: UUID): ObjectNode = mapper.readTree(mvc.perform(
        post("/api/v1/matches/export").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsBytes(mapOf("matchIds" to ids.toList()))))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray) as ObjectNode

    private fun import(source: JsonNode): JsonNode = mapper.readTree(mvc.perform(
        post("/api/v1/matches/import").contentType(MediaType.APPLICATION_JSON).content(source.toString()))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray)

    private fun statistics(id: UUID): JsonNode = mapper.readTree(mvc.perform(get("/api/v1/matches/$id/statistics"))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray)
}
