package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.repository.ListPage
import com.github.mihanizzm.ultistats.repository.ListPageRequest
import com.github.mihanizzm.ultistats.repository.jpa.ListQueryRepository
import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Suppress("unused")
class TeamServiceImpl(
    private val listQueries: ListQueryRepository,
    private val teamRepository: SpringDataTeamRepository,
) : TeamService {
    private val log = LoggerFactory.getLogger(TeamServiceImpl::class.java)

    override fun get(teamId: UUID): Team? = teamRepository.findByIdAndDeletedAtIsNull(teamId)

    override fun getForUpdate(teamId: UUID): Team? = teamRepository.findByIdForUpdate(teamId)

    @Transactional
    override fun create(team: Team) {
        teamRepository.save(team)
    }

    @Transactional
    override fun update(team: Team) {
        teamRepository.save(team)
    }

    @Transactional
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

    override fun findPage(filter: TeamFilterRequest, request: ListPageRequest): ListPage<Team> =
        listQueries.teams(filter, request)

    override fun count(): Long = teamRepository.countByDeletedAtIsNull()

    override fun countFiltered(filter: TeamFilterRequest): Long = listQueries.countTeams(filter)

}
