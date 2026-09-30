package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.TeamPlayer
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataPlayerRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamPlayerRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamRepository
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import jakarta.servlet.ServletException
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertIs

@Suppress("NonAsciiCharacters")
@SpringBootTest
@AutoConfigureMockMvc
@Import(DeletionTransactionTestConfiguration::class)
class DeletionTransactionIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var scenarioFactory: DeletionTransactionScenarioFactory

    @MockitoSpyBean
    lateinit var playerService: PlayerService

    @MockitoSpyBean
    lateinit var teamService: TeamService

    @Test
    fun `Ошибка удаления игрока откатывает удаление всех его членств`() {
        val scenario = scenarioFactory.createPlayerWithTwoTeams()
        doThrow(IllegalStateException("player delete failed"))
            .`when`(playerService)
            .delete(scenario.player.id)

        val exception = assertThrows<ServletException> {
            mockMvc.perform(delete("/api/v1/players/${scenario.player.id}"))
        }
        assertIs<IllegalStateException>(exception.cause)

        mockMvc.perform(get("/api/v1/players/${scenario.player.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memberships.length()").value(2))
            .andExpect(
                jsonPath("$.memberships[*].teamId").value(
                    containsInAnyOrder(*scenario.teams.map { it.id.toString() }.toTypedArray()),
                ),
            )
    }

    @Test
    fun `Ошибка удаления команды откатывает удаление всех ее членств`() {
        val scenario = scenarioFactory.createTeamWithTwoPlayers()
        doThrow(IllegalStateException("team delete failed"))
            .`when`(teamService)
            .delete(scenario.team.id)

        val exception = assertThrows<ServletException> {
            mockMvc.perform(delete("/api/v1/teams/${scenario.team.id}"))
        }
        assertIs<IllegalStateException>(exception.cause)

        mockMvc.perform(get("/api/v1/teams/${scenario.team.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.players.length()").value(2))
            .andExpect(
                jsonPath("$.players[*].playerId").value(
                    containsInAnyOrder(*scenario.players.map { it.id.toString() }.toTypedArray()),
                ),
            )
    }
}

data class PlayerWithTeams(
    val player: Player,
    val teams: List<Team>,
)

data class TeamWithPlayers(
    val team: Team,
    val players: List<Player>,
)

class DeletionTransactionScenarioFactory(
    private val playerRepository: SpringDataPlayerRepository,
    private val teamRepository: SpringDataTeamRepository,
    private val teamPlayerRepository: SpringDataTeamPlayerRepository,
) {
    fun createPlayerWithTwoTeams(): PlayerWithTeams {
        val player = playerRepository.save(Player(UUID.randomUUID(), "Delete", "Player"))
        val teams = listOf(
            teamRepository.save(Team(UUID.randomUUID(), "First team")),
            teamRepository.save(Team(UUID.randomUUID(), "Second team")),
        )
        teams.forEachIndexed { index, team ->
            teamPlayerRepository.save(TeamPlayer(team.id, player.id, index + 1))
        }
        return PlayerWithTeams(player, teams)
    }

    fun createTeamWithTwoPlayers(): TeamWithPlayers {
        val team = teamRepository.save(Team(UUID.randomUUID(), "Delete team"))
        val players = listOf(
            playerRepository.save(Player(UUID.randomUUID(), "First", "Player")),
            playerRepository.save(Player(UUID.randomUUID(), "Second", "Player")),
        )
        players.forEachIndexed { index, player ->
            teamPlayerRepository.save(TeamPlayer(team.id, player.id, index + 1))
        }
        return TeamWithPlayers(team, players)
    }
}

@TestConfiguration
class DeletionTransactionTestConfiguration {
    @Bean
    fun deletionTransactionScenarioFactory(
        playerRepository: SpringDataPlayerRepository,
        teamRepository: SpringDataTeamRepository,
        teamPlayerRepository: SpringDataTeamPlayerRepository,
    ) = DeletionTransactionScenarioFactory(playerRepository, teamRepository, teamPlayerRepository)
}
