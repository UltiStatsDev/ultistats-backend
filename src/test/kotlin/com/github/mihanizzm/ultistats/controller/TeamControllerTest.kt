package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class TeamControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var teamService: TeamService
    @Autowired lateinit var playerService: PlayerService
    @Autowired lateinit var teamPlayerService: TeamPlayerService

    private lateinit var team: Team
    private lateinit var player: Player

    @BeforeEach
    fun setUp() {
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
        team = Team(UUID.randomUUID(), "Team", "City")
        player = Player(UUID.randomUUID(), "First", "Player")
        teamService.create(team); playerService.create(player)
    }

    @Test
    fun `team CRUD does not accept roster as entity state`() {
        mockMvc.perform(post("/api/v1/teams").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"New","city":"Town"}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.players.length()").value(0))
    }

    @Test
    fun `membership put creates and updates stable relationship`() {
        val url = "/api/v1/teams/${team.id}/players/${player.id}"
        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""{"number":7}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.teamId").value(team.id.toString()))
            .andExpect(jsonPath("$.teamName").value("Team"))
            .andExpect(jsonPath("$.playerId").value(player.id.toString()))
            .andExpect(jsonPath("$.number").value(7))
        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""{"number":17}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.number").value(17))
        mockMvc.perform(get("/api/v1/teams/${team.id}/players"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
        mockMvc.perform(get("/api/v1/teams/${team.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.players[0].teamId").value(team.id.toString()))
            .andExpect(jsonPath("$.players[0].number").value(17))
    }

    @Test
    fun `membership delete returns 204 and removes relationship`() {
        teamPlayerService.add(team.id, player.id, 8)
        mockMvc.perform(delete("/api/v1/teams/${team.id}/players/${player.id}"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/teams/${team.id}/players"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `duplicate active number in team returns 409`() {
        val other = Player(UUID.randomUUID(), "Other", "Player")
        playerService.create(other)
        teamPlayerService.add(team.id, player.id, 8)
        mockMvc.perform(put("/api/v1/teams/${team.id}/players/${other.id}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"number":8}"""))
            .andExpect(status().isConflict)
    }

    @Test
    fun `membership endpoints return 404 for missing resources`() {
        mockMvc.perform(put("/api/v1/teams/${team.id}/players/${UUID.randomUUID()}")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/teams/${UUID.randomUUID()}/players")).andExpect(status().isNotFound)
    }
}
