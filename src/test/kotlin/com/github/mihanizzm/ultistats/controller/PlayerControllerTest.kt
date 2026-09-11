package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import org.hamcrest.Matchers.containsInAnyOrder
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
class PlayerControllerTest {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var playerService: PlayerService
    @Autowired lateinit var teamService: TeamService
    @Autowired lateinit var teamPlayerService: TeamPlayerService

    @BeforeEach
    fun setUp() {
        teamService.getAll().forEach { teamService.delete(it.id) }
        playerService.getAll().forEach { playerService.delete(it.id) }
    }

    @Test
    fun `player CRUD contains no implicit membership fields`() {
        val created = mockMvc.perform(post("/api/v1/players").contentType(MediaType.APPLICATION_JSON)
            .content("""{"firstName":"Ivan","lastName":"Ivanov"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.memberships.length()").value(0))
            .andExpect(jsonPath("$.team").doesNotExist())
            .andExpect(jsonPath("$.number").doesNotExist())
            .andReturn().response.contentAsString
        val id = objectMapper.readTree(created).get("id").asText()
        mockMvc.perform(put("/api/v1/players/$id").contentType(MediaType.APPLICATION_JSON)
            .content("""{"firstName":"Petr"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.firstName").value("Petr"))
        mockMvc.perform(delete("/api/v1/players/$id")).andExpect(status().isNoContent)
    }

    @Test
    fun `player detail and teams endpoint return every explicit membership`() {
        val player = Player(UUID.randomUUID(), "Multi", "Player")
        val team1 = Team(UUID.randomUUID(), "One")
        val team2 = Team(UUID.randomUUID(), "Two")
        playerService.create(player); teamService.create(team1); teamService.create(team2)
        teamPlayerService.add(team1.id, player.id, 7)
        teamPlayerService.add(team2.id, player.id, 17)

        mockMvc.perform(get("/api/v1/players/${player.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memberships.length()").value(2))
            .andExpect(jsonPath("$.memberships[*].teamName").value(containsInAnyOrder("One", "Two")))
        mockMvc.perform(get("/api/v1/players/${player.id}/teams"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].playerId").value(player.id.toString()))
            .andExpect(jsonPath("$[1].playerId").value(player.id.toString()))
            .andExpect(jsonPath("$[*].teamName").value(containsInAnyOrder("One", "Two")))
    }

    @Test
    fun `player list stays lightweight and can filter by membership`() {
        val team = Team(UUID.randomUUID(), "One")
        val included = Player(UUID.randomUUID(), "Included", "Player")
        val excluded = Player(UUID.randomUUID(), "Excluded", "Player")
        teamService.create(team); playerService.create(included); playerService.create(excluded)
        teamPlayerService.add(team.id, included.id, 4)

        mockMvc.perform(get("/api/v1/players").param("teamId", team.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(included.id.toString()))
            .andExpect(jsonPath("$.content[0].memberships").doesNotExist())
            .andExpect(jsonPath("$.content[0].number").doesNotExist())
    }

    @Test
    fun `player list supports omitted and populated name filters`() {
        playerService.create(Player(UUID.randomUUID(), "Ivan", "Petrov"))
        playerService.create(Player(UUID.randomUUID(), "Anna", "Ivanova"))

        mockMvc.perform(get("/api/v1/players"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))

        mockMvc.perform(get("/api/v1/players").param("name", "ivan petr"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].firstName").value("Ivan"))
    }
}
