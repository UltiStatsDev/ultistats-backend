package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.CreateTeamRequest
import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateTeamRequest
import com.github.mihanizzm.ultistats.dto.response.PhotoUrlResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerTeamMembershipResponse
import com.github.mihanizzm.ultistats.dto.response.TeamDetailResponse
import com.github.mihanizzm.ultistats.dto.response.TeamListItemResponse
import com.github.mihanizzm.ultistats.dto.response.TeamPlayerResponse
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.service.LocalFileStorageService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.util.SortingUtils.applySorting
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Component
class TeamFacade(
    private val teamService: TeamService,
    private val playerService: PlayerService,
    private val teamPlayerService: TeamPlayerService,
    private val localFileStorageService: LocalFileStorageService,
) {
    companion object {
        val DEFAULT_SORT = SortParam("name")
        val SORT_FIELD_EXTRACTORS: Map<String, (Team) -> Comparable<*>?> = mapOf("name" to { it.name })
    }

    private fun detail(team: Team): TeamDetailResponse {
        val memberships = teamPlayerService.getByTeamId(team.id)
        val players = playerService.getAllByIds(memberships.map { it.playerId }).associateBy { it.id }
        return TeamDetailResponse.from(team, memberships, players)
    }

    fun getAllPaged(page: Int, size: Int, filter: TeamFilterRequest, sortParam: SortParam = DEFAULT_SORT): PageResponse<TeamListItemResponse> {
        val teams = teamService.findAllFiltered(filter)
        val content = teams.applySorting(sortParam, SORT_FIELD_EXTRACTORS)
            .drop(page * size).take(size).map(TeamListItemResponse::from)
        return PageResponse.of(content, teams.size.toLong(), page, size)
    }

    fun getById(id: UUID): TeamDetailResponse? = teamService.get(id)?.let(::detail)

    fun getMemberships(id: UUID): List<TeamPlayerResponse>? {
        if (teamService.get(id) == null) return null
        return teamPlayerService.getByTeamId(id).map(TeamPlayerResponse::from)
    }

    fun create(request: CreateTeamRequest): TeamDetailResponse {
        val team = Team(UUID.randomUUID(), request.name, request.city)
        teamService.create(team)
        return detail(team)
    }

    fun update(id: UUID, request: UpdateTeamRequest): TeamDetailResponse? {
        val existing = teamService.get(id) ?: return null
        val updated = existing.copy(
            name = request.name ?: existing.name,
            city = request.city ?: existing.city,
        )
        teamService.update(updated)
        return detail(updated)
    }

    fun delete(id: UUID): Boolean {
        if (teamService.get(id) == null) return false
        teamPlayerService.getByTeamId(id).forEach { teamPlayerService.remove(id, it.playerId) }
        teamService.delete(id)
        return true
    }

    fun putPlayer(teamId: UUID, playerId: UUID, number: Int?): PlayerTeamMembershipResponse? {
        require(number == null || number >= 0) { "Player number cannot be negative" }
        val team = teamService.get(teamId) ?: return null
        if (playerService.get(playerId) == null) return null
        return PlayerTeamMembershipResponse.from(teamPlayerService.add(teamId, playerId, number), team)
    }

    fun removePlayer(teamId: UUID, playerId: UUID): Boolean {
        if (teamService.get(teamId) == null || playerService.get(playerId) == null) return false
        return teamPlayerService.remove(teamId, playerId)
    }

    fun uploadPhoto(teamId: UUID, file: MultipartFile): PhotoUrlResponse? {
        val team = teamService.get(teamId) ?: return null
        val url = localFileStorageService.upload(file) ?: return null
        teamService.update(team.copy(photoUrl = url))
        return PhotoUrlResponse(url)
    }

    fun getPhotoUrl(teamId: UUID): PhotoUrlResponse? = teamService.get(teamId)?.photoUrl?.let(::PhotoUrlResponse)

    fun deletePhotoUrl(teamId: UUID): PhotoUrlResponse? {
        val team = teamService.get(teamId) ?: return null
        val oldUrl = team.photoUrl
        teamService.update(team.copy(photoUrl = null))
        return PhotoUrlResponse(oldUrl)
    }
}
