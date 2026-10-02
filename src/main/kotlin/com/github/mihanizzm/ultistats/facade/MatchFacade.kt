package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.common.SortParam
import com.github.mihanizzm.ultistats.dto.request.CreateMatchRequest
import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import com.github.mihanizzm.ultistats.dto.request.MatchTimestampRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateMatchRequest
import com.github.mihanizzm.ultistats.dto.response.MatchListItemResponse
import com.github.mihanizzm.ultistats.dto.response.MatchResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.TeamService
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import com.github.mihanizzm.ultistats.util.SortingUtils.applySorting
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MatchFacade(
    private val matchService: MatchService,
    private val teamService: TeamService,
) {
    companion object {
        val DEFAULT_SORT = SortParam("plannedStartTimestamp")

        val SORT_FIELD_EXTRACTORS: Map<String, (Match) -> Comparable<*>?> = mapOf(
            "plannedStartTimestamp" to { it.plannedStartTimestamp },
            "startedAt" to { it.startedAt },
            "endedAt" to { it.endedAt },
            "status" to { it.status.name },
        )
    }

    fun getAll(): List<MatchResponse> =
        matchService.getAll().map { match ->
            val teams = teamService.getAllInListIncludingDeleted(match.teamIds)
            MatchResponse.from(match, teams.associateBy { it.id })
        }

    fun getAllPaged(
        page: Int,
        size: Int,
        filter: MatchFilterRequest,
        sortParam: SortParam = DEFAULT_SORT,
    ): PageResponse<MatchListItemResponse> {
        val filteredMatches = matchService.findAllFiltered(filter)
        val totalElements = filteredMatches.size.toLong()

        val sortedMatches = filteredMatches.applySorting(sortParam, SORT_FIELD_EXTRACTORS)

        val content = sortedMatches
            .drop(page * size)
            .take(size)
            .map { match ->
                val teams = teamService.getAllInListIncludingDeleted(match.teamIds)
                MatchListItemResponse.from(match, teams.associateBy { it.id })
            }

        return PageResponse.of(content, totalElements, page, size)
    }

    fun getById(id: UUID): MatchResponse? {
        val match = matchService.get(id) ?: return null
        val teams = teamService.getAllInListIncludingDeleted(match.teamIds)
        return MatchResponse.from(match, teams.associateBy { it.id })
    }

    fun create(request: CreateMatchRequest): MatchCommandResult<MatchResponse> {
        validateTeamSelection(request.teamIds)?.let { return it }
        validatePlayersPerTeam(request.playersPerTeam)?.let { return it }
        val match = Match(
            id = UUID.randomUUID(),
            teamIds = request.teamIds,
            plannedStartTimestamp = request.plannedStartTimestamp,
            playersPerTeam = request.playersPerTeam,
        )
        matchService.create(match)
        return MatchCommandResult.Success(matchService.getOrThrow(match.id).toResponse())
    }

    fun update(id: UUID, request: UpdateMatchRequest): MatchCommandResult<MatchResponse> {
        val result = matchService.update(id, request.teamIds, request.plannedStartTimestamp, request.playersPerTeam)
        return result.toResponse()
    }

    fun delete(id: UUID): Boolean {
        if (matchService.get(id) == null) return false
        matchService.delete(id)
        return true
    }

    fun startMatch(id: UUID, request: MatchTimestampRequest): MatchCommandResult<MatchResponse> {
        return matchService.startMatch(id, request.timestamp).toResponse()
    }

    fun endMatch(id: UUID, request: MatchTimestampRequest): MatchCommandResult<MatchResponse> {
        return matchService.endMatch(id, request.timestamp).toResponse()
    }

    private fun validateTeamSelection(teamIds: List<UUID>): MatchCommandResult.InvalidRequest? = when {
        teamIds.size != 2 || teamIds.distinct().size != 2 -> invalidRequest("A match must have exactly two distinct teams")
        teamService.getAllInList(teamIds).size != teamIds.size -> invalidRequest("All selected teams must exist and be active")
        else -> null
    }

    private fun validatePlayersPerTeam(playersPerTeam: Int): MatchCommandResult.InvalidRequest? =
        if (playersPerTeam < Match.MIN_PLAYERS_PER_TEAM) {
            invalidRequest("Players per team must be at least ${Match.MIN_PLAYERS_PER_TEAM}")
        } else {
            null
        }

    private fun invalidRequest(detail: String) = MatchCommandResult.InvalidRequest(
        MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid match request", detail),
    )

    private fun MatchCommandResult<Match>.toResponse(): MatchCommandResult<MatchResponse> = when (this) {
        is MatchCommandResult.Success -> {
            MatchCommandResult.Success(value.toResponse())
        }
        MatchCommandResult.NotFound -> MatchCommandResult.NotFound
        is MatchCommandResult.InvalidRequest -> this
        is MatchCommandResult.InvalidState -> this
        is MatchCommandResult.Conflict -> this
    }

    private fun Match.toResponse(): MatchResponse {
        val teams = teamService.getAllInListIncludingDeleted(teamIds)
        return MatchResponse.from(this, teams.associateBy { it.id })
    }
}
