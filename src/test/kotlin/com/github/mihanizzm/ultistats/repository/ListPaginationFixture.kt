package com.github.mihanizzm.ultistats.repository

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchTeam
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.model.TeamPlayer
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataPlayerRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataTeamPlayerRepository
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics

/** Focused setup and persistence-observation helper for the HTTP list flow tests. */
class ListPaginationFixture(
    private val players: SpringDataPlayerRepository,
    private val teams: SpringDataTeamRepository,
    private val memberships: SpringDataTeamPlayerRepository,
    private val matches: SpringDataMatchRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val em: EntityManager,
) {
    fun save(player: Player): Player = players.save(player)
    fun save(team: Team): Team = teams.save(team)
    fun saveMemberships(rows: List<TeamPlayer>) { memberships.saveAll(rows) }
    fun saveMatch(match: Match) {
        matches.save(match)
        match.teamIds.forEachIndexed { index, id ->
            matchTeams.save(MatchTeam(match.id, id, teams.findById(id).orElseThrow().name, index + 1))
        }
    }
    fun flushAndClear() { em.flush(); em.clear() }
    fun resetLoadStatistics(): Statistics {
        em.clear()
        return em.entityManagerFactory.unwrap(SessionFactory::class.java).statistics.also { it.clear() }
    }
}
