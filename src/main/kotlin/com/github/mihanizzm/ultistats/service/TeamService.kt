package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.model.Team
import java.util.UUID

interface TeamService {
    fun get(teamId: UUID): Team?

    fun getForUpdate(teamId: UUID): Team?

    fun create(team: Team)

    fun update(team: Team)

    fun delete(teamId: UUID)

    fun getAll(): List<Team>

    fun getAllInList(ids: List<UUID>): List<Team>

    fun getAllInListIncludingDeleted(ids: List<UUID>): List<Team>

    fun findAllFiltered(filter: TeamFilterRequest): List<Team>

    fun count(): Long

    fun countFiltered(filter: TeamFilterRequest): Long
}
