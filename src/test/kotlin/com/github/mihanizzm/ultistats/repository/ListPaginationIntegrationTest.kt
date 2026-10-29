package com.github.mihanizzm.ultistats.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerListItemResponse
import com.github.mihanizzm.ultistats.dto.response.TeamListItemResponse
import com.github.mihanizzm.ultistats.dto.response.MatchListItemResponse
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.github.mihanizzm.ultistats.dto.common.SortDirection
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.TeamPlayer
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PlayerService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@Transactional
@AutoConfigureMockMvc
@Import(ListPaginationFixture::class)
class ListPaginationIntegrationTest {
    @Autowired lateinit var fixture: ListPaginationFixture
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var matchService: MatchService
    @Autowired lateinit var playerService: PlayerService

    @Test
    fun `equal names have stable signed UUID ascending ties in either direction`() {
        val ids = listOf("80000000-0000-0000-0000-000000000000", "00000000-0000-0000-8000-000000000000",
            "00000000-0000-0000-0000-000000000000", "7fffffff-ffff-ffff-ffff-ffffffffffff").map(UUID::fromString)
        ids.reversed().forEach { fixture.save(Player(it, "Tie63", "Tie63")) }
        fixture.flushAndClear()
        for (direction in SortDirection.entries) {
            val actual = (0..3).map { page ->
                playerPage(page, 1, PlayerFilterRequest(name = "Tie63"), SortParam("lastName", direction)).content.single().id
            }
            assertEquals(ids, actual)
        }
    }

    @Test
    fun `database limits loaded entities and counts all filtered rows including past last page`() {
        repeat(35) { fixture.save(Player(UUID.randomUUID(), "Bound63", "Name$it")) }
        fixture.flushAndClear()
        val stats = fixture.resetLoadStatistics()
        val page = playerPage(2, 4, PlayerFilterRequest(name = "Bound63"))
        assertEquals(35, page.totalElements)
        assertEquals(4, page.content.size)
        assertTrue(stats.entityLoadCount <= 4, "A page must not load the full table: ${stats.entityLoadCount} entities")
        val empty = playerPage(Int.MAX_VALUE, 100, PlayerFilterRequest(name = "Bound63"))
        assertTrue(empty.content.isEmpty())
        assertEquals(35, empty.totalElements)
    }

    @Test
    fun `strings retain case sensitive UTF16 ordering including supplementary Unicode`() {
        val names = listOf("Unicode63-A", "Unicode63-Z", "Unicode63-a", "Unicode63-é", "Unicode63-😀", "Unicode63-\uE000")
        names.reversed().forEach { fixture.save(Team(UUID.randomUUID(), it)) }
        fixture.flushAndClear()
        for (direction in SortDirection.entries) {
            val result = teamPage(0, 100, TeamFilterRequest("Unicode63-"), SortParam("name", direction))
            assertEquals(if (direction == SortDirection.ASC) names else names.reversed(), result.content.map { it.name })
        }
    }

    @Test
    fun `player membership and name filters exclude deleted players and memberships without duplicating count`() {
        val team = fixture.save(Team(UUID.randomUUID(), "Membership63"))
        val otherTeam = fixture.save(Team(UUID.randomUUID(), "Other63"))
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val selected = fixture.save(Player(UUID.randomUUID(), "Member63", "Selected"))
        val removed = fixture.save(Player(UUID.randomUUID(), "Member63", "Removed"))
        val deleted = fixture.save(Player(UUID.randomUUID(), "Member63", "Deleted", deletedAt = now))
        val wrongName = fixture.save(Player(UUID.randomUUID(), "Other", "Other"))
        fixture.save(Player(UUID.randomUUID(), "Member63", "NoMembership"))
        fixture.saveMemberships(listOf(TeamPlayer(team.id, selected.id), TeamPlayer(otherTeam.id, selected.id),
            TeamPlayer(team.id, removed.id, deletedAt = now), TeamPlayer(team.id, deleted.id), TeamPlayer(team.id, wrongName.id)))
        fixture.flushAndClear()
        val filter = PlayerFilterRequest("member63", team.id)
        val result = playerPage(0, 1, filter)
        assertEquals(listOf(selected.id), result.content.map { it.id })
        assertEquals(1, result.totalElements)
        val stats = fixture.resetLoadStatistics()
        assertEquals(1, playerService.countFiltered(filter))
        assertEquals(0, stats.entityLoadCount)
    }

    @Test
    fun `team page and count use the same name and soft delete predicates`() {
        fixture.save(Team(UUID.randomUUID(), "Count63-Alpha"))
        fixture.save(Team(UUID.randomUUID(), "Count63-Beta"))
        fixture.save(Team(UUID.randomUUID(), "Count63-Deleted", deletedAt = Instant.now()))
        fixture.flushAndClear()
        val result = teamPage(1, 1, TeamFilterRequest("count63"))
        assertEquals(listOf("Count63-Beta"), result.content.map { it.name })
        assertEquals(2, result.totalElements)
        assertEquals(2, result.totalPages)
        assertEquals(0, teamPage(0, 5, TeamFilterRequest("NoSuchTeam63")).totalElements)
    }

    @Test
    fun `match filters count status dates and teams in SQL and hydrate only the page`() {
        val teamIds = listOf(fixture.save(Team(UUID.randomUUID(), "Match63-A")).id,
            fixture.save(Team(UUID.randomUUID(), "Match63-B")).id)
        val day = Instant.parse("2026-01-01T12:00:00Z")
        val ids = (1..5).map { UUID(0, it.toLong()) }
        val fixtures = listOf(
            Match(ids[0], teamIds, plannedStartTimestamp = day, startedAt = day, endedAt = day),
            Match(ids[1], teamIds, plannedStartTimestamp = day, startedAt = day),
            Match(ids[2], teamIds, plannedStartTimestamp = day),
            Match(ids[3], teamIds),
            Match(ids[4], teamIds, plannedStartTimestamp = day, deletedAt = day),
        )
        fixtures.reversed().forEach(fixture::saveMatch)
        fixture.flushAndClear()
        val stats = fixture.resetLoadStatistics()
        val filter = MatchFilterRequest(teamId = teamIds.first(), dateFrom = day, dateTo = day)
        val page = matchPage(1, 1, filter, SortParam("status"))
        assertEquals(listOf(ids[1]), page.content.map { it.id })
        assertEquals(3, page.totalElements)
        assertEquals(teamIds, page.content.single().teams.map { it.teamId })
        assertTrue(stats.getEntityStatistics(Match::class.java.name).loadCount <= 1)
        for ((field, asc, desc) in listOf(
            Triple("plannedStartTimestamp", listOf(0, 1, 2, 3), listOf(3, 0, 1, 2)),
            Triple("startedAt", listOf(0, 1, 2, 3), listOf(2, 3, 0, 1)),
            Triple("endedAt", listOf(0, 1, 2, 3), listOf(1, 2, 3, 0)),
            Triple("status", listOf(0, 1, 2, 3), listOf(2, 3, 1, 0)),
        )) {
            for (direction in SortDirection.entries) {
                val result = matchPage(0, 10, MatchFilterRequest(teamId = teamIds.first()), SortParam(field, direction))
                assertEquals((if (direction == SortDirection.ASC) asc else desc).map(ids::get), result.content.map { it.id }, "$field $direction")
            }
        }
        val planned = matchPage(0, 1, filter.copy(status = MatchStatus.PLANNED))
        assertEquals(listOf(ids[2]), planned.content.map { it.id })
        assertEquals(1, planned.totalElements)
        assertEquals(0, matchPage(0, 5, filter.copy(dateFrom = day.plusSeconds(1), dateTo = null)).totalElements)
        fixture.resetLoadStatistics()
        assertEquals(3, matchService.countFiltered(filter))
        assertEquals(0, stats.entityLoadCount)
    }

    @Test
    fun `name filters keep backslashes literal and preserve existing SQL wildcard behavior`() {
        fixture.save(Team(UUID.randomUUID(), "Pattern63-\\x"))
        fixture.save(Team(UUID.randomUUID(), "Pattern63-xx"))
        fixture.save(Player(UUID.randomUUID(), "Pattern63-\\x", "Name"))
        fixture.save(Player(UUID.randomUUID(), "Pattern63-xx", "Name"))
        fixture.flushAndClear()
        assertEquals(listOf("Pattern63-\\x"), teamPage(0, 10, TeamFilterRequest("Pattern63-\\")).content.map { it.name })
        assertEquals(listOf("Pattern63-\\x"), playerPage(0, 10, PlayerFilterRequest("Pattern63-\\")).content.map { it.firstName })
        assertEquals(2, teamPage(0, 10, TeamFilterRequest("Pattern63-%")).totalElements)
        assertEquals(2, playerPage(0, 10, PlayerFilterRequest("Pattern63-__")).totalElements)
    }

    private fun playerPage(page: Int, size: Int, filter: PlayerFilterRequest, sort: SortParam = SortParam("lastName")): PageResponse<PlayerListItemResponse> =
        mapper.readValue(list("players", page, size, sort, mapOf("name" to filter.name, "teamId" to filter.teamId?.toString())))

    private fun teamPage(page: Int, size: Int, filter: TeamFilterRequest, sort: SortParam = SortParam("name")): PageResponse<TeamListItemResponse> =
        mapper.readValue(list("teams", page, size, sort, mapOf("name" to filter.name)))

    private fun matchPage(page: Int, size: Int, filter: MatchFilterRequest, sort: SortParam = SortParam("plannedStartTimestamp")): PageResponse<MatchListItemResponse> =
        mapper.readValue(list("matches", page, size, sort, mapOf("teamId" to filter.teamId?.toString(), "status" to filter.status?.name,
            "dateFrom" to filter.dateFrom?.toString(), "dateTo" to filter.dateTo?.toString())))

    private fun list(resource: String, page: Int, size: Int, sort: SortParam, filters: Map<String, String?>): String {
        val request = get("/api/v1/$resource").param("page", page.toString()).param("size", size.toString())
            .param("sort", "${sort.field}:${sort.direction.name.lowercase()}")
        filters.forEach { (key, value) -> if (value != null) request.param(key, value) }
        return mvc.perform(request).andExpect(status().isOk).andReturn().response.contentAsString
    }
}
