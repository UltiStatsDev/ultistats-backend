package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.repository.ListPage
import com.github.mihanizzm.ultistats.repository.ListPageRequest
import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.service.patch.MatchPatch
import java.time.Instant
import java.util.UUID

interface MatchService {
    fun get(matchId: UUID): Match?

    fun getOrThrow(matchId: UUID): Match

    fun create(match: Match)

    fun update(
        matchId: UUID,
        teamIds: List<UUID>?,
        plannedStartTimestamp: Instant?,
        playersPerTeam: Int? = null,
    ): MatchCommandResult<Match>

    fun update(match: Match): MatchCommandResult<Match>

    fun patch(matchId: UUID, patch: MatchPatch): MatchCommandResult<Match>

    fun delete(matchId: UUID)

    fun getAll(): List<Match>

    fun findAllFiltered(filter: MatchFilterRequest): List<Match>

    fun findPage(filter: MatchFilterRequest, request: ListPageRequest): ListPage<Match>

    fun count(): Long

    fun countFiltered(filter: MatchFilterRequest): Long

    fun recalculateScore(matchId: UUID)

    fun startMatch(matchId: UUID, timestamp: Instant): MatchCommandResult<Match>

    fun endMatch(matchId: UUID, timestamp: Instant): MatchCommandResult<Match>

    fun getForUpdate(matchId: UUID): Match?
}
