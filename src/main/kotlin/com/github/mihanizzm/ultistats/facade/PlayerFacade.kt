package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.CreatePlayerRequest
import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.dto.request.UpdatePlayerRequest
import com.github.mihanizzm.ultistats.dto.response.PhotoUrlResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerDetailResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerListItemResponse
import com.github.mihanizzm.ultistats.dto.response.PlayerTeamMembershipResponse
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.service.LocalFileStorageService
import com.github.mihanizzm.ultistats.service.PlayerService
import com.github.mihanizzm.ultistats.service.TeamPlayerService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.util.SortingUtils.applySorting
import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Component
class PlayerFacade(
    private val playerService: PlayerService,
    private val teamPlayerService: TeamPlayerService,
    private val teamService: TeamService,
    private val localFileStorageService: LocalFileStorageService,
) {
    companion object {
        val DEFAULT_SORT = SortParam("lastName")
        val SORT_FIELD_EXTRACTORS: Map<String, (Player) -> Comparable<*>?> = mapOf(
            "lastName" to { it.lastName },
            "firstName" to { it.firstName },
        )
    }

    fun getAllPaged(page: Int, size: Int, filter: PlayerFilterRequest, sortParam: SortParam = DEFAULT_SORT): PageResponse<PlayerListItemResponse> {
        val players = playerService.findAllFiltered(filter)
        val content = players.applySorting(sortParam, SORT_FIELD_EXTRACTORS)
            .drop(page * size).take(size).map(PlayerListItemResponse::from)
        return PageResponse.of(content, players.size.toLong(), page, size)
    }

    fun getById(id: UUID): PlayerDetailResponse? = playerService.get(id)?.let {
        PlayerDetailResponse.from(it, getMembershipResponses(id))
    }

    fun getMemberships(id: UUID): List<PlayerTeamMembershipResponse>? {
        if (playerService.get(id) == null) return null
        return getMembershipResponses(id)
    }

    fun create(request: CreatePlayerRequest): PlayerDetailResponse {
        val player = Player(UUID.randomUUID(), request.firstName, request.lastName)
        playerService.create(player)
        return PlayerDetailResponse.from(player, emptyList())
    }

    fun update(id: UUID, request: UpdatePlayerRequest): PlayerDetailResponse? {
        val existing = playerService.get(id) ?: return null
        val updated = existing.copy(
            firstName = request.firstName ?: existing.firstName,
            lastName = request.lastName ?: existing.lastName,
        )
        playerService.update(updated)
        return PlayerDetailResponse.from(updated, getMembershipResponses(id))
    }

    fun delete(id: UUID): Boolean {
        if (playerService.get(id) == null) return false
        teamPlayerService.getByPlayerId(id).forEach { teamPlayerService.remove(it.teamId, id) }
        playerService.delete(id)
        return true
    }

    fun uploadPhoto(playerId: UUID, file: MultipartFile): PhotoUrlResponse? {
        val player = playerService.get(playerId) ?: return null
        val url = localFileStorageService.upload(file) ?: return null
        playerService.update(player.copy(photoUrl = url))
        return PhotoUrlResponse(url)
    }

    fun getPhotoUrl(playerId: UUID): PhotoUrlResponse? =
        playerService.get(playerId)?.photoUrl?.let(::PhotoUrlResponse)

    fun deletePhotoUrl(playerId: UUID): PhotoUrlResponse? {
        val player = playerService.get(playerId) ?: return null
        val oldUrl = player.photoUrl
        playerService.update(player.copy(photoUrl = null))
        return PhotoUrlResponse(oldUrl)
    }

    private fun getMembershipResponses(playerId: UUID): List<PlayerTeamMembershipResponse> {
        val memberships = teamPlayerService.getByPlayerId(playerId)
        val teamsById = teamService.getAllInListIncludingDeleted(memberships.map { it.teamId })
            .associateBy { it.id }
        return memberships.map { membership ->
            PlayerTeamMembershipResponse.from(
                membership,
                requireNotNull(teamsById[membership.teamId]) {
                    "Team ${membership.teamId} referenced by player membership does not exist"
                },
            )
        }
    }
}
