package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.model.Player
import java.util.UUID

interface PlayerService {
    fun get(playerId: UUID): Player?

    fun getForUpdate(playerId: UUID): Player?

    fun create(player: Player)

    fun update(player: Player)

    fun delete(playerId: UUID)

    fun getAll(): List<Player>

    fun getAllByIds(ids: List<UUID>): List<Player>

    fun getAllByTeamId(teamId: UUID): List<Player>

    fun findAllFiltered(filter: PlayerFilterRequest): List<Player>

    fun count(): Long

    fun countFiltered(filter: PlayerFilterRequest): Long
}
