package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Suppress("unused")
class TeamServiceImpl(
    private val teamRepository: SpringDataTeamRepository,
) : TeamService {
    private val log = LoggerFactory.getLogger(TeamServiceImpl::class.java)

    override fun get(teamId: UUID): Team? = teamRepository.findByIdAndDeletedAtIsNull(teamId)

    override fun getForUpdate(teamId: UUID): Team? = teamRepository.findByIdForUpdate(teamId)

    override fun create(team: Team) {
        teamRepository.save(team)
    }

    override fun update(team: Team) {
        teamRepository.save(team)
    }

    override fun delete(teamId: UUID) {
        get(teamId)?.let { teamRepository.save(it.copy(deletedAt = Instant.now())) }
    }

    override fun getAll(): List<Team> = teamRepository.findAllByDeletedAtIsNull()

    override fun getAllInList(ids: List<UUID>): List<Team> =
        teamRepository.findAllByIdInAndDeletedAtIsNull(ids)

    override fun getAllInListIncludingDeleted(ids: List<UUID>): List<Team> =
        teamRepository.findAllById(ids).toList()

    override fun findAllFiltered(filter: TeamFilterRequest): List<Team> {
        val teams = if (filter.name != null) {
            teamRepository.findByNameContainingIgnoreCase(filter.name)
        } else {
            teamRepository.findAllByDeletedAtIsNull()
        }
        return teams
    }

    override fun count(): Long = teamRepository.countByDeletedAtIsNull()

    override fun countFiltered(filter: TeamFilterRequest): Long =
        if (filter.name != null) {
            teamRepository.countByNameContainingIgnoreCase(filter.name)
        } else {
            teamRepository.countByDeletedAtIsNull()
        }
}
