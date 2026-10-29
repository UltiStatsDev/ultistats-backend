package com.github.mihanizzm.ultistats.fixture

import com.github.mihanizzm.ultistats.model.*
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.repository.jpa.*
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Source-database setup only. Tests exercise the real transfer controllers and public reads. */
@Transactional
open class MatchTransferFixtureHelper(
    private val players: SpringDataPlayerRepository,
    private val teams: SpringDataTeamRepository,
    private val memberships: SpringDataTeamPlayerRepository,
    private val matches: SpringDataMatchRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val events: SpringDataEventRepository,
) {
    open fun clear() {
        events.deleteAllInBatch()
        participants.deleteAllInBatch()
        matchTeams.deleteAllInBatch()
        matches.deleteAllInBatch()
        memberships.deleteAllInBatch()
        players.deleteAllInBatch()
        teams.deleteAllInBatch()
    }

    open fun saveTeam(team: Team) { teams.saveAndFlush(team) }
    open fun savePlayer(player: Player) { players.saveAndFlush(player) }
    open fun addMembership(teamId: UUID, playerId: UUID, number: Int) {
        memberships.saveAndFlush(TeamPlayer(teamId, playerId, number))
    }

    open fun createMatch(match: Match) {
        matches.saveAndFlush(match)
        matchTeams.saveAllAndFlush(match.teamIds.mapIndexed { index, id ->
            MatchTeam(match.id, id, teams.findById(id).orElseThrow().name, index + 1)
        })
        participants.saveAllAndFlush(match.teamIds.flatMap { teamId ->
            memberships.findAllByTeamIdAndDeletedAtIsNull(teamId).map { membership ->
                val player = players.findById(membership.playerId).orElseThrow()
                MatchParticipant.player(match.id, teamId, player.id, player.firstName, player.lastName, membership.number)
            } + (1..2).map { MatchParticipant.unknown(match.id, teamId, it) }
        })
    }

    open fun start(id: UUID, at: Instant) {
        val match = matches.findById(id).orElseThrow()
        match.startedAt = at
        matches.saveAndFlush(match)
    }

    open fun end(id: UUID, at: Instant) {
        val match = matches.findById(id).orElseThrow()
        match.endedAt = at
        matches.saveAndFlush(match)
    }

    open fun addEvent(event: Event, id: UUID) {
        val sequence = (events.findFirstByMatchIdOrderBySequenceNumberDesc(id)?.sequenceNumber ?: 0) + 1
        events.saveAndFlush(EventEntity.fromDomain(UUID.randomUUID(), id, sequence, event))
        if (event.type == EventType.GOAL || event.type == EventType.CALLAHAN) {
            val scorer = (event as TwoPlayerEvent).toParticipant
            val teamId = participants.findAllByMatchId(id).single { it.participantId == scorer }.teamId
            val team = matchTeams.findAllByMatchIdOrderByPosition(id).single { it.teamId == teamId }
            team.score += 1
            matchTeams.saveAndFlush(team)
        }
    }

    open fun removeEvent(eventId: UUID, matchId: UUID) {
        val event = requireNotNull(events.findByIdAndMatchIdAndDeletedAtIsNull(eventId, matchId))
        events.saveAndFlush(event.copy(deletedAt = Instant.now()))
    }

    open fun deleteMatch(id: UUID) {
        val match = matches.findById(id).orElseThrow()
        matches.saveAndFlush(Match(id, emptyList(), plannedStartTimestamp = match.plannedStartTimestamp,
            playersPerTeam = match.playersPerTeam, startedAt = match.startedAt, endedAt = match.endedAt, deletedAt = Instant.now()))
    }
    open fun deleteTeam(id: UUID) { teams.saveAndFlush(teams.findById(id).orElseThrow().copy(deletedAt = Instant.now())) }
    open fun deletePlayer(id: UUID) { players.saveAndFlush(players.findById(id).orElseThrow().copy(deletedAt = Instant.now())) }

    /** Reproduces a historical snapshot whose catalog player no longer exists. */
    open fun removeCatalogPlayer(id: UUID) {
        memberships.deleteAll(memberships.findAll().filter { it.playerId == id })
        memberships.flush()
        players.deleteById(id)
        players.flush()
    }
}
