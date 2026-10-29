package com.github.mihanizzm.ultistats.transfer

import com.github.mihanizzm.ultistats.model.*
import com.github.mihanizzm.ultistats.repository.jpa.*
import com.github.mihanizzm.ultistats.service.MatchService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MatchTransferService(
    private val matches: SpringDataMatchRepository,
    private val matchTeams: SpringDataMatchTeamRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val events: SpringDataEventRepository,
    private val teams: SpringDataTeamRepository,
    private val players: SpringDataPlayerRepository,
    private val matchService: MatchService,
    private val validator: MatchTransferValidator,
) {
    /** All queries see one source snapshot, even while an in-progress match changes. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun export(matchIds: List<UUID>): MatchTransferPackage {
        if (matchIds.isEmpty() || matchIds.distinct().size != matchIds.size) {
            throw InvalidMatchTransferException("matchIds", "Select at least one match, without duplicate IDs")
        }
        val portableMatches = matchIds.map { id ->
            val match = matches.findByIdAndDeletedAtIsNull(id) ?: throw ExportMatchNotFoundException(id)
            val roster = participants.findAllByMatchId(id)
            PortableMatch(
                id, match.playersPerTeam, match.plannedStartTimestamp, match.startedAt, match.endedAt,
                matchTeams.findAllByMatchIdOrderByPosition(id).map { team ->
                    PortableMatchTeam(team.teamId, team.teamName,
                        roster.filter { it.teamId == team.teamId }.sortedBy { it.participantId }.map {
                            PortableParticipant(it.participantId, it.kind, it.unknownSlot, it.firstName, it.lastName, it.number)
                        })
                },
                events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(id).map {
                    PortableEvent(it.id, it.sequenceNumber, it.eventType, it.occurredAt, it.fromParticipantId, it.toParticipantId, it.teamId)
                },
            )
        }
        val sourceTeams = portableMatches.flatMap { it.teams }.distinctBy { it.teamId }
        // Walk all snapshots: a later selected match may introduce more players on a shared team.
        val allPlayers = portableMatches.flatMap { it.teams }.flatMap { it.participants }
            .filter { it.kind == MatchParticipantKind.PLAYER }.distinctBy { it.participantId }
        val catalogTeams = teams.findAllById(sourceTeams.map { it.teamId }).associateBy { it.id }
        val catalogPlayers = players.findAllById(allPlayers.map { it.participantId }).associateBy { it.id }
        return MatchTransferPackage(
            1, portableMatches,
            sourceTeams.map { snapshot ->
                val current = catalogTeams[snapshot.teamId]
                PortableTeam(snapshot.teamId, current?.name ?: snapshot.teamName, current?.city)
            },
            allPlayers.map { snapshot ->
                val current = catalogPlayers[snapshot.participantId]
                PortablePlayer(snapshot.participantId, current?.firstName ?: requireNotNull(snapshot.firstName),
                    current?.lastName ?: requireNotNull(snapshot.lastName))
            },
        ).also(validator::validate)
    }

    @Transactional
    fun importPackage(data: MatchTransferPackage): MatchTransferResult {
        validator.validate(data)
        val matchIds = freshIds(data.matches.map { it.id })
        val teamIds = freshIds(data.teams.map { it.id })
        val playerIds = freshIds(data.players.map { it.id })
        val eventIds = freshIds(data.matches.flatMap { it.events }.map { it.id })
        val participantIds = data.matches.associate { match ->
            match.id to match.teams.flatMap { it.participants }.associate { participant ->
                participant.participantId to if (participant.kind == MatchParticipantKind.PLAYER)
                    playerIds.getValue(participant.participantId) else UUID.randomUUID()
            }
        }
        teams.saveAllAndFlush(data.teams.map { Team(teamIds.getValue(it.id), it.name, it.city) })
        players.saveAllAndFlush(data.players.map { Player(playerIds.getValue(it.id), it.firstName, it.lastName) })
        data.matches.forEach { source ->
            val id = matchIds.getValue(source.id)
            val participantMap = participantIds.getValue(source.id)
            matches.saveAndFlush(Match(id, source.teams.map { teamIds.getValue(it.teamId) },
                plannedStartTimestamp = source.plannedStartTimestamp, playersPerTeam = source.playersPerTeam,
                startedAt = source.startedAt, endedAt = source.endedAt))
            matchTeams.saveAllAndFlush(source.teams.mapIndexed { index, team ->
                MatchTeam(id, teamIds.getValue(team.teamId), team.teamName, index + 1)
            })
            participants.saveAllAndFlush(source.teams.flatMap { team ->
                team.participants.map { participant ->
                    MatchParticipant(id, participantMap.getValue(participant.participantId), teamIds.getValue(team.teamId),
                        participant.kind, participant.unknownSlot, participant.firstName, participant.lastName, participant.number)
                }
            })
            events.saveAllAndFlush(source.events.map { event ->
                EventEntity(eventIds.getValue(event.id), id, event.sequenceNumber, event.type, event.occurredAt,
                    event.fromParticipantId?.let(participantMap::getValue), event.toParticipantId?.let(participantMap::getValue),
                    event.teamId?.let(teamIds::getValue))
            })
            matchService.recalculateScore(id)
        }
        return MatchTransferResult(matchIds, teamIds, playerIds, eventIds, participantIds)
    }

    private fun freshIds(ids: List<UUID>): Map<UUID, UUID> = ids.associateWith { UUID.randomUUID() }
}
