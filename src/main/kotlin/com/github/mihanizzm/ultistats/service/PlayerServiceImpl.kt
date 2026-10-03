package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataPlayerRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class PlayerServiceImpl(
    private val playerRepository: SpringDataPlayerRepository,
    private val teamPlayerService: TeamPlayerService,
) : PlayerService {
    private val log = LoggerFactory.getLogger(PlayerServiceImpl::class.java)

    override fun get(playerId: UUID): Player? = playerRepository.findByIdAndDeletedAtIsNull(playerId)

    override fun getForUpdate(playerId: UUID): Player? = playerRepository.findByIdForUpdate(playerId)

    override fun create(player: Player) {
        playerRepository.save(player)
    }

    override fun update(player: Player) {
        playerRepository.save(player)
    }

    override fun delete(playerId: UUID) {
        get(playerId)?.let { playerRepository.save(it.copy(deletedAt = Instant.now())) }
    }

    override fun getAll(): List<Player> = playerRepository.findAllByDeletedAtIsNull()

    override fun getAllByIds(ids: List<UUID>): List<Player> =
        playerRepository.findAllByIdInAndDeletedAtIsNull(ids)

    override fun getAllByIdsIncludingDeleted(ids: List<UUID>): List<Player> =
        playerRepository.findAllById(ids).toList()

    override fun getAllByTeamId(teamId: UUID): List<Player> {
        val playerIds = teamPlayerService.getByTeamId(teamId).map { it.playerId }
        return playerRepository.findAllByIdInAndDeletedAtIsNull(playerIds)
    }

    override fun findAllFiltered(filter: PlayerFilterRequest): List<Player> {
        val players = playerRepository.findFiltered(filter.name ?: "")
        if (filter.teamId == null) return players
        val playerIds = teamPlayerService.getByTeamId(filter.teamId).mapTo(mutableSetOf()) { it.playerId }
        return players.filter { it.id in playerIds }
    }

    override fun count(): Long = playerRepository.countByDeletedAtIsNull()

    override fun countFiltered(filter: PlayerFilterRequest): Long =
        findAllFiltered(filter).size.toLong()
}
