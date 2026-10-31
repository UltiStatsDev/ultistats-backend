package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@SpringBootTest
@AutoConfigureMockMvc
@Suppress("NonAsciiCharacters")
class StatisticsControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var matchService: MatchService

    @Autowired
    lateinit var teamService: TeamService

    @Autowired
    lateinit var playerService: PlayerService

    @Autowired
    lateinit var eventService: EventService

    @Autowired
    lateinit var teamPlayerService: TeamPlayerService

    private lateinit var team1: Team
    private lateinit var team2: Team
    private lateinit var players1: List<Player>
    private lateinit var players2: List<Player>
    private lateinit var match: Match

    @BeforeEach
    fun setup() {
        matchService.getAll().forEach { matchService.delete(it.id) }
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }

        val (t1, p1) = createTestTeam("Команда 1")
        val (t2, p2) = createTestTeam("Команда 2")
        team1 = t1
        team2 = t2
        players1 = p1
        players2 = p2
        match = createTestMatch(team1, team2)
        matchService.startMatch(match.id, MATCH_STARTED_AT)
    }

    @Test
    fun `Статистика события возвращает сгруппированный контракт со снимками и миллисекундами`() {
        eventService.create(OnePlayerEvent(players1[0].id, MATCH_STARTED_AT.plusSeconds(1), EventType.PULL), match.id)
        eventService.create(OnePlayerEvent(players1[0].id, MATCH_STARTED_AT.plusSeconds(2), EventType.PICKUP), match.id)
        eventService.create(TwoPlayerEvent(players1[0].id, players1[1].id, MATCH_STARTED_AT.plusSeconds(3), EventType.PASS), match.id)

        teamService.update(team1.copy(name = "Переименованная команда"))
        playerService.update(players1[0].copy(firstName = "Новый", lastName = "Игрок"))

        mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchId").value(match.id.toString()))
            .andExpect(jsonPath("$.teams.length()").value(2))
            .andExpect(jsonPath("$.teams[0].teamId").value(team1.id.toString()))
            .andExpect(jsonPath("$.teams[0].teamName").value("Команда 1"))
            .andExpect(jsonPath("$.teams[1].teamId").value(team2.id.toString()))
            .andExpect(jsonPath("$.teams[1].teamName").value("Команда 2"))
            .andExpect(jsonPath("$.teams[0].participants[0].firstName").value("Игрок"))
            .andExpect(jsonPath("$.teams[0].participants[0].lastName").value("Один"))
            .andExpect(jsonPath("$.teams[0].participants[0].displayName").value("Игрок Один"))
            .andExpect(jsonPath("$.teams[0].attack.allPasses").value(1))
            .andExpect(jsonPath("$.teams[0].attack.breaks").value(0))
            .andExpect(jsonPath("$.teams[1].attack.breaks").value(0))
            .andExpect(jsonPath("$.time.totalTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[0].time.possessionTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[0].participants[0].time.possessionTimeMs").isNumber)
            .andExpect(jsonPath("$.playerStatistics").doesNotExist())
            .andExpect(jsonPath("$.teamStatistics").doesNotExist())
    }

    @Test
    fun `UNKNOWN break is rebuilt after undo and deleted canonical goals`() {
        val unknowns = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .filter { it.kind == MatchParticipantKind.UNKNOWN }
        eventService.create(OnePlayerEvent(unknowns[0].participantId, MATCH_STARTED_AT.plusSeconds(1), EventType.PULL), match.id)
        eventService.create(OnePlayerEvent(unknowns[0].participantId, MATCH_STARTED_AT.plusSeconds(2), EventType.PICKUP), match.id)
        val goal = TwoPlayerEvent(unknowns[0].participantId, unknowns[1].participantId, MATCH_STARTED_AT.plusSeconds(3), EventType.GOAL)
        eventService.create(goal, match.id)

        fun assertBreaks(expected: Int) {
            mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.teams[0].attack.breaks").value(expected))
                .andExpect(jsonPath("$.teams[0].attack.score").value(expected))
                .andExpect(jsonPath("$.teams[1].attack.breaks").value(0))
        }
        assertBreaks(1)
        eventService.undoLast(match.id)
        assertBreaks(0)
        eventService.create(goal, match.id)
        assertBreaks(1)
        eventService.remove(eventService.getAllEventsOfMatch(match.id).last().id, match.id)
        assertBreaks(0)
    }

    @Test
    fun `UNKNOWN defender Callahan counts a single break and keeps defense and time`() {
        val unknown = matchService.getOrThrow(match.id).participantsByTeam.getValue(team1.id)
            .first { it.kind == MatchParticipantKind.UNKNOWN }
        eventService.create(OnePlayerEvent(players1[0].id, MATCH_STARTED_AT.plusSeconds(1), EventType.PULL), match.id)
        eventService.create(OnePlayerEvent(players2[0].id, MATCH_STARTED_AT.plusSeconds(2), EventType.PICKUP), match.id)
        eventService.create(TwoPlayerEvent(players2[0].id, unknown.participantId, MATCH_STARTED_AT.plusSeconds(5), EventType.CALLAHAN), match.id)
        mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.teams[0].attack.breaks").value(1))
            .andExpect(jsonPath("$.teams[0].attack.score").value(1))
            .andExpect(jsonPath("$.teams[0].defense.callahans").value(1))
            .andExpect(jsonPath("$.teams[1].attack.breaks").value(0))
            .andExpect(jsonPath("$.teams[1].attack.score").value(0))
            .andExpect(jsonPath("$.teams[1].time.possessionTimeMs").value(3000))
            .andExpect(jsonPath("$.time.totalTimeMs").value(4000))
    }

    @Test
    fun `Незаполняемые и дублирующиеся поля не публикуются в статистике`() {
        val response = mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andExpect(status().isOk)
            .andReturn()

        val statistics = objectMapper.readTree(response.response.contentAsString)
        assertThat(statistics["teams"].map { it["attack"]["breaks"].intValue() }).containsExactly(0, 0)
        val removedFields = listOf(
            "saves",
            "saveGoals",
            "possessionTime",
            "percentOfPossession",
            "timeSpentOnViolationDiscussions",
        )

        removedFields.forEach { field ->
            assertThat(statistics.findValues(field))
                .describedAs("Поле %s не должно входить в Statistics API", field)
                .isEmpty()
        }
    }

    @Test
    fun `Статистика включает четыре неизвестных участника с явными nullable полями`() {
        val expectedUnknownIds = matchService.getOrThrow(match.id).participantsByTeam.values.flatten()
            .filter { it.kind == MatchParticipantKind.UNKNOWN }
            .map { it.participantId.toString() }
            .toSet()

        val response = mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andExpect(status().isOk)
            .andReturn()
        val teams = objectMapper.readTree(response.response.contentAsString).get("teams")
        val participants = teams.flatMap { team -> team.get("participants").toList() }
        val unknowns = participants.filter { it.get("kind").asText() == MatchParticipantKind.UNKNOWN.name }

        assertThat(participants).allSatisfy { participant ->
            assertThat(participant.hasNonNull("participantId")).isTrue()
            assertThat(participant.has("playerId")).isFalse()
        }
        assertThat(unknowns.map { it.get("participantId").asText() })
            .containsExactlyInAnyOrderElementsOf(expectedUnknownIds)
        assertThat(unknowns).hasSize(4)
        assertThat(unknowns).allSatisfy { unknown ->
            assertThat(unknown.has("firstName")).isTrue()
            assertThat(unknown.get("firstName").isNull).isTrue()
            assertThat(unknown.has("lastName")).isTrue()
            assertThat(unknown.get("lastName").isNull).isTrue()
            assertThat(unknown.has("number")).isTrue()
            assertThat(unknown.get("number").isNull).isTrue()
            assertThat(unknown.get("displayName").asText())
                .isEqualTo("Неизвестный игрок ${unknown.get("unknownSlot").asInt()}")
        }
    }

    @Test
    fun `Несуществующий матч возвращает структурированный 404`() {
        val missingId = UUID.randomUUID()
        val instance = "/api/v1/matches/$missingId/statistics"

        mockMvc.perform(get(instance))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.title").value("Resource not found"))
            .andExpect(jsonPath("$.detail").value("Match $missingId not found"))
            .andExpect(jsonPath("$.instance").value(instance))
    }

    @Test
    fun `Мягко удаленный матч возвращает структурированный 404`() {
        matchService.delete(match.id)
        val instance = "/api/v1/matches/${match.id}/statistics"

        mockMvc.perform(get(instance))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value(instance))
    }

    @Test
    fun `Временная статистика публикуется числом миллисекунд`() {
        eventService.create(OnePlayerEvent(players1[0].id, MATCH_STARTED_AT.plusSeconds(1), EventType.PULL), match.id)
        eventService.create(OnePlayerEvent(players2[0].id, MATCH_STARTED_AT.plusSeconds(10), EventType.PICKUP), match.id)
        eventService.create(TeamEvent(team2.id, MATCH_STARTED_AT.plusSeconds(15), EventType.TIMEOUT_START), match.id)
        eventService.create(TeamEvent(team2.id, MATCH_STARTED_AT.plusSeconds(75), EventType.TIMEOUT_END), match.id)
        eventService.create(TwoPlayerEvent(players2[0].id, players2[1].id, MATCH_STARTED_AT.plusSeconds(90), EventType.PASS), match.id)
        eventService.create(TwoPlayerEvent(players2[1].id, players2[2].id, MATCH_STARTED_AT.plusSeconds(100), EventType.GOAL), match.id)

        mockMvc.perform(get("/api/v1/matches/${match.id}/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.time.totalTimeMs").isNumber)
            .andExpect(jsonPath("$.time.betweenPointsTimeMs").isNumber)
            .andExpect(jsonPath("$.time.timeoutTimeMs").isNumber)
            .andExpect(jsonPath("$.time.halftimeTimeMs").isNumber)
            .andExpect(jsonPath("$.time.pureGameTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[1].time.possessionTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[1].time.betweenPointsTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[1].time.timeoutTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[1].participants[0].time.possessionTimeMs").isNumber)
            .andExpect(jsonPath("$.teams[1].participants[0].time.averagePossessionTimeMs").isNumber)
    }

    @Test
    fun `Экспорт скачивает ZIP с CSV таблицами и snapshot данными`() {
        val response = mockMvc.perform(
            get("/api/v1/matches/${match.id}/statistics/export")
                .accept("application/zip"),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/zip"))
            .andExpect(
                header().string(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"match_${match.id}_statistics.zip\"",
                ),
            )
            .andReturn()

        val entries = readZipEntries(response.response.contentAsByteArray)
        assertThat(entries.keys).containsExactly("match.csv", "teams.csv", "participants.csv")
        assertThat(entries.getValue("match.csv")).startsWith("match_id,total_time_ms,")
        assertThat(entries.getValue("teams.csv"))
            .contains("${match.id},${team1.id},Команда 1,")
            .contains("${match.id},${team2.id},Команда 2,")
        assertThat(entries.getValue("participants.csv"))
            .contains("${players1[0].id},PLAYER,,Игрок,Один,Игрок Один,1,")
            .contains("UNKNOWN,1,,,Неизвестный игрок 1,,")
    }

    @Test
    fun `Экспорт отсутствующего матча возвращает структурированный 404`() {
        val missingId = UUID.randomUUID()
        val instance = "/api/v1/matches/$missingId/statistics/export"

        mockMvc.perform(get(instance).accept("application/zip"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Match $missingId not found"))
            .andExpect(jsonPath("$.instance").value(instance))
    }

    @Test
    fun `OpenAPI документирует стабильный ответ и ProblemDetail`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].schema['\$ref']")
                .value("#/components/schemas/MatchStatisticsResponse"))
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].example.time.totalTimeMs").isNumber)
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].example.teams[0].participants[0].displayName").value("Ivan Ivanov"))
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].example.teams[0].participants[0].attack.passes").isNumber)
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].example.teams[0].participants[0].time.possessionTimeMs").isNumber)
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['404'].content['application/problem+json'].schema['\$ref']")
                .value("#/components/schemas/ProblemDetail"))
    }

    @Test
    fun `OpenAPI документирует ZIP экспорт и ProblemDetail`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.paths['/api/v1/matches/{matchId}/statistics/export'].get.responses['200'].content['application/zip'].schema.type")
                    .value("string"),
            )
            .andExpect(
                jsonPath("$.paths['/api/v1/matches/{matchId}/statistics/export'].get.responses['200'].content['application/zip'].schema.format")
                    .value("binary"),
            )
            .andExpect(
                jsonPath("$.paths['/api/v1/matches/{matchId}/statistics/export'].get.responses['404'].content['application/problem+json'].schema['\$ref']")
                    .value("#/components/schemas/ProblemDetail"),
            )
    }

    @Test
    fun `OpenAPI documents nonnegative integer team breaks and example`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.components.schemas.TeamAttackStatisticsResponse.properties.breaks.type").value("integer"))
            .andExpect(jsonPath("$.components.schemas.TeamAttackStatisticsResponse.properties.breaks.minimum").value(0))
            .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/statistics'].get.responses['200'].content['application/json'].example.teams[0].attack.breaks").value(1))
    }

    private fun createTestTeam(name: String): Pair<Team, List<Player>> {
        val teamId = UUID.randomUUID()
        val players = listOf(
            Player(UUID.randomUUID(), "Игрок", "Один"),
            Player(UUID.randomUUID(), "Игрок", "Два"),
            Player(UUID.randomUUID(), "Игрок", "Три"),
        )
        val team = Team(id = teamId, name = name)
        teamService.create(team)
        players.forEach { playerService.create(it) }
        players.forEachIndexed { index, player -> teamPlayerService.add(teamId, player.id, index + 1) }
        return team to players
    }

    private fun createTestMatch(team1: Team, team2: Team): Match {
        val match = Match(id = UUID.randomUUID(), teamIds = listOf(team1.id, team2.id))
        matchService.create(match)
        return match
    }

    private fun readZipEntries(content: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        private val MATCH_STARTED_AT = Instant.parse("2025-01-01T00:00:00Z")
    }
}
