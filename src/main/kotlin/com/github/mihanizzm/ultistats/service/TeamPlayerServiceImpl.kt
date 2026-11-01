package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.TeamPlayer
import com.github.mihanizzm.ultistats.model.TeamPlayerId
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamPlayerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class TeamPlayerServiceImpl(
    private val repository: SpringDataTeamPlayerRepository,
) : TeamPlayerService {
    override fun get(teamId: UUID, playerId: UUID): TeamPlayer? =
        repository.findByTeamIdAndPlayerIdAndDeletedAtIsNull(teamId, playerId)

    override fun getByTeamId(teamId: UUID): List<TeamPlayer> =
        repository.findAllByTeamIdAndDeletedAtIsNull(teamId)

    override fun getByPlayerId(playerId: UUID): List<TeamPlayer> =
        repository.findAllByPlayerIdAndDeletedAtIsNull(playerId)

    @Transactional
    override fun add(teamId: UUID, playerId: UUID, number: Int?): TeamPlayer {
        val id = TeamPlayerId(teamId, playerId)
        val membership = repository.findById(id).orElse(null)
            ?.copy(number = number, deletedAt = null)
            ?: TeamPlayer(teamId, playerId, number)
        return repository.save(membership)
    }

    @Transactional
    override fun remove(teamId: UUID, playerId: UUID): Boolean {
        val membership = get(teamId, playerId) ?: return false
        repository.save(membership.copy(deletedAt = Instant.now()))
        return true
    }
}
