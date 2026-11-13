package com.github.mihanizzm.ultistats.transfer

import com.github.mihanizzm.ultistats.model.*
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.repository.jpa.*
import com.github.mihanizzm.ultistats.service.MatchAnnouncementService
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.PointLineupProblemCode
import com.github.mihanizzm.ultistats.service.PointLineupRejectedException
import com.github.mihanizzm.ultistats.service.PossessionChangeWrites
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import org.springframework.beans.factory.annotation.Value
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
    private val announcements: MatchAnnouncementRepository,
    private val teams: SpringDataTeamRepository,
    private val players: SpringDataPlayerRepository,
    private val matchService: MatchService,
    private val announcementService: MatchAnnouncementService,
    private val possessionChangeWrites: PossessionChangeWrites,
    private val validator: MatchTransferValidator,
    private val lineups: PointLineupRepository,
    @Value("\${ultistats.point-lineups.write-enabled:false}") private val pointLineupWritesEnabled: Boolean,
    @Value("\${ultistats.point-substitutions.write-enabled:false}") private val substitutionWritesEnabled: Boolean,
    @Value("\${ultistats.point-incoming-reserves.write-enabled:false}") private val incomingReserveWritesEnabled: Boolean,
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
            val snapshotTeams = matchTeams.findAllByMatchIdOrderByPosition(id)
            val journal = events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(id)
            PortableMatch(
                id, match.playersPerTeam, match.plannedStartTimestamp, match.effectiveStartedAt, match.effectiveEndedAt,
                snapshotTeams.map { team ->
                    PortableMatchTeam(team.teamId, team.teamName,
                        roster.filter { it.teamId == team.teamId }.sortedBy { it.participantId }.map {
                            PortableParticipant(it.participantId, it.kind, it.unknownSlot, it.firstName, it.lastName, it.number)
                        })
                },
                journal.map {
                    PortableEvent(it.id, it.sequenceNumber, it.eventType, it.effectiveOccurredAt,
                        it.fromSlotId ?: it.fromParticipantId, it.toSlotId ?: it.toParticipantId, it.teamId)
                },
                announcements.list(id).map {
                    PortableAnnouncement(it.id, it.teamId, it.startedAt, it.endedAt)
                },
                exportLineups(match, snapshotTeams.map { it.teamId }, journal),
            )
        }
        val sourceTeams = portableMatches.flatMap { it.teams }.distinctBy { it.teamId }
        // Walk all snapshots: a later selected match may introduce more players on a shared team.
        val allPlayers = portableMatches.flatMap { it.teams }.flatMap { it.participants }
            .filter { it.kind == MatchParticipantKind.PLAYER }.distinctBy { it.participantId }
        val catalogTeams = teams.findAllById(sourceTeams.map { it.teamId }).associateBy { it.id }
        val catalogPlayers = players.findAllById(allPlayers.map { it.participantId }).associateBy { it.id }
        return MatchTransferPackage(
            portableMatches.requiredFormatVersion(), portableMatches,
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
        if (data.matches.containsSubstitutions() && !substitutionWritesEnabled) {
            throw PointLineupRejectedException(
                PointLineupProblemCode.POINT_SUBSTITUTION_WRITE_DISABLED, "Point substitution writes are disabled",
            )
        }
        if (data.matches.containsIncomingReserves() && !incomingReserveWritesEnabled) {
            throw PointLineupRejectedException(
                PointLineupProblemCode.POINT_SUBSTITUTION_WRITE_DISABLED, "Incoming reserve writes are disabled",
            )
        }
        if (data.matches.containsPossessionChanges()) {
            possessionChangeWrites.rejection(EventType.POSSESSION_SET)?.let {
                throw TransferWriteDisabledException(it)
            }
        }
        if (data.matches.any { it.announcements.isNotEmpty() }) announcementService.requireNewIntervalsEnabled()
        if (data.matches.any { it.pointLineups.isNotEmpty() } && !pointLineupWritesEnabled) {
            throw PointLineupRejectedException(
                PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled",
            )
        }
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
            val slotMap = freshIds(source.pointLineups.flatMap { lineup ->
                lineup.teams.flatMap { team -> team.entries.filterIsInstance<PortablePointLineupTemporary>().map { it.slotId } } +
                    lineup.substitutionSlots.orEmpty().map { it.slotId }
            })
            val actors = participantMap + slotMap
            val journal = source.events.map { event ->
                StoredEvent(eventIds.getValue(event.id), event.sequenceNumber, event.copy(
                    fromParticipantId = event.fromParticipantId?.let(actors::getValue),
                    toParticipantId = event.toParticipantId?.let(actors::getValue),
                    teamId = event.teamId?.let(teamIds::getValue),
                ).toDomain())
            }
            val incoming = insertLineups(source, id, journal, eventIds, teamIds, participantMap, slotMap)
            // The events reference slot rows, and the introduction links reference those events.
            incoming.forEach { lineups.insertIncomingSlot(id, it) }
            val slots = slotMap.values.toSet()
            events.saveAllAndFlush(journal.map { EventEntity.fromDomain(it.id, id, it.sequenceNumber, it.event, slotIds = slots) })
            incoming.forEach { lineups.bindIntroduction(id, it) }
            for (interval in source.announcements) {
                announcements.insert(id, MatchAnnouncement(
                    interval.id, interval.teamId?.let(teamIds::getValue), interval.startedAt, interval.endedAt,
                ))
            }
            matchService.recalculateScore(id)
        }
        return MatchTransferResult(matchIds, teamIds, playerIds, eventIds, participantIds)
    }

    private fun exportLineups(match: Match, teamIds: List<UUID>, journal: List<EventEntity>): List<PortablePointLineup> {
        val saved = lineups.list(match.id, teamIds)
        if (saved.isEmpty() && journal.none { it.fromSlotId != null || it.toSlotId != null }) return emptyList()
        val timeline = PointTimeline.from(match.id, match.status, journal.map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) })
        val eventIds = journal.mapTo(mutableSetOf()) { it.id }
        val introducedByPoint = lineups.introducedSlots(match.id)
            .filter { it.slot.introducedByEventId in eventIds }
            .groupBy({ it.pointId }, { it.slot })
        val slotOpenings = mutableMapOf<UUID, UUID?>()
        val portable = timeline.mapNotNull { point ->
            val lineup = saved[point.pointId] ?: return@mapNotNull null
            val teams = lineup.teams.map { team ->
                PortablePointLineupTeam(team.teamId, team.entries.map { entry ->
                    when (entry) {
                        is PointLineupPlayer -> PortablePointLineupPlayer(entry.participantId)
                        is PointLineupTemporary -> {
                            slotOpenings[entry.slotId] = point.openingEventId
                            PortablePointLineupTemporary(entry.slotId, entry.number, entry.resolvedParticipantId)
                        }
                    }
                })
            }
            val introduced = introducedByPoint[point.pointId].orEmpty().map { slot ->
                slotOpenings[slot.slotId] = point.openingEventId
                PortableSubstitutionSlot(slot.teamId, slot.slotId, slot.number, slot.resolvedParticipantId, slot.introducedByEventId)
            }
            PortablePointLineup(point.openingEventId, teams, introduced.takeIf { it.isNotEmpty() })
        }
        val closings = timeline.mapNotNull { it.closingEventId }.toSet()
        var opening: UUID? = null
        for (event in journal) {
            for (slot in listOfNotNull(event.fromSlotId, event.toSlotId)) {
                if (slot !in slotOpenings || slotOpenings[slot] != opening) {
                    throw ExportPointLineupUnsupportedException(match.id)
                }
            }
            if (event.id in closings) opening = event.id
        }
        return portable
    }

    private fun insertLineups(
        source: PortableMatch, matchId: UUID, journal: List<StoredEvent>, eventIds: Map<UUID, UUID>,
        teamIds: Map<UUID, UUID>, participantIds: Map<UUID, UUID>, slotIds: Map<UUID, UUID>,
    ): List<IntroducedPointSlot> {
        if (source.pointLineups.isEmpty()) return emptyList()
        val points = PointTimeline.from(matchId, source.status, journal).associateBy { it.openingEventId }
        return source.pointLineups.flatMap { lineup ->
            val point = points.getValue(lineup.openingEventId?.let(eventIds::getValue))
            val teams = lineup.teams.map { team ->
                PointLineupTeam(teamIds.getValue(team.teamId), team.entries.map { entry ->
                    when (entry) {
                        is PortablePointLineupPlayer -> PointLineupPlayer(participantIds.getValue(entry.participantId))
                        is PortablePointLineupTemporary -> PointLineupTemporary(
                            slotIds.getValue(entry.slotId), entry.number, entry.resolvedParticipantId?.let(participantIds::getValue),
                        )
                    }
                })
            }
            lineups.insert(matchId, point, PointLineup(revision = 1, teams = teams))
            lineup.substitutionSlots.orEmpty().map { slot ->
                IntroducedPointSlot(point.pointId, PointSubstitutionSlot(
                    teamIds.getValue(slot.teamId), slotIds.getValue(slot.slotId), slot.number,
                    slot.resolvedParticipantId?.let(participantIds::getValue), eventIds.getValue(slot.introducedByEventId),
                ))
            }
        }
    }

    private fun freshIds(ids: List<UUID>): Map<UUID, UUID> = ids.associateWith { UUID.randomUUID() }
}

class TransferWriteDisabledException(val problem: MatchProblem) : RuntimeException(problem.detail)

class ExportPointLineupUnsupportedException(id: UUID) :
    RuntimeException("Match $id has a live temporary actor outside its exportable point lineup")
