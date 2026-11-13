package com.github.mihanizzm.ultistats.transfer

import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.repository.jpa.IntroducedPointSlot
import com.github.mihanizzm.ultistats.service.ConfirmedJournal
import com.github.mihanizzm.ultistats.service.PointLineupRejectedException
import com.github.mihanizzm.ultistats.validation.event.EventSequenceDecision
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import org.springframework.stereotype.Component
import java.util.UUID

/** Validates the entire graph without accessing or modifying the destination database. */
@Component
class MatchTransferValidator(private val sequencePolicy: EventSequencePolicy) {
    fun validate(data: MatchTransferPackage) {
        val expectedVersion = data.matches.requiredFormatVersion()
        check(data.formatVersion == expectedVersion, "formatVersion",
            "Expected version $expectedVersion for the included active events, announcements, point lineups and incoming reserves")
        check(data.formatVersion == 6 || data.matches.none { match -> match.pointLineups.any { it.substitutionSlots != null } },
            "matches.pointLineups.substitutionSlots", "Substitution slots require format version 6")
        check(data.matches.isNotEmpty(), "matches", "Select at least one match")
        unique(data.matches.map { it.id }, "matches")
        unique(data.teams.map { it.id }, "teams")
        unique(data.players.map { it.id }, "players")
        unique(data.matches.flatMap { it.events }.map { it.id }, "matches.events")
        val teamIds = data.teams.map { it.id }.toSet()
        val playerIds = data.players.map { it.id }.toSet()
        check(teamIds == data.matches.flatMap { it.teams }.map { it.teamId }.toSet(), "teams", "Catalog must contain exactly the referenced teams")
        check(playerIds == data.matches.flatMap { it.teams }.flatMap { it.participants }
            .filter { it.kind == MatchParticipantKind.PLAYER }.map { it.participantId }.toSet(),
            "players", "Catalog must contain exactly the referenced players")
        data.teams.forEachIndexed { i, team ->
            text(team.name, "teams[$i].name", 255)
            team.city?.let { check(it.length <= 127, "teams[$i].city", "Maximum length is 127") }
        }
        data.players.forEachIndexed { i, player ->
            text(player.firstName, "players[$i].firstName", 255)
            text(player.lastName, "players[$i].lastName", 255)
        }
        data.matches.forEachIndexed { index, match -> validateMatch(match, "matches[$index]") }
    }

    private fun validateMatch(match: PortableMatch, path: String) {
        check(match.playersPerTeam >= 2, "$path.playersPerTeam", "Minimum is 2")
        check(match.teams.size == 2, "$path.teams", "Exactly two teams are required")
        unique(match.teams.map { it.teamId }, "$path.teams")
        unique(match.teams.flatMap { it.participants }.map { it.participantId }, "$path.teams.participants")
        match.teams.forEachIndexed { t, team ->
            val teamPath = "$path.teams[$t]"
            text(team.teamName, "$teamPath.teamName", 255)
            val unknowns = team.participants.filter { it.kind == MatchParticipantKind.UNKNOWN }
            check(unknowns.map { it.unknownSlot }.toSet() == setOf(1, 2) && unknowns.size == 2,
                "$teamPath.participants", "Exactly UNKNOWN slots 1 and 2 are required")
            val numbers = team.participants.mapNotNull { it.number }
            check(numbers.distinct().size == numbers.size, "$teamPath.participants.number", "Numbers must be unique within each team")
            team.participants.forEachIndexed { p, participant ->
                val participantPath = "$teamPath.participants[$p]"
                when (participant.kind) {
                    MatchParticipantKind.PLAYER -> {
                        check(participant.unknownSlot == null, "$participantPath.unknownSlot", "PLAYER cannot have an unknown slot")
                        text(participant.firstName, "$participantPath.firstName", 255)
                        text(participant.lastName, "$participantPath.lastName", 255)
                        check(participant.number == null || participant.number >= 0, "$participantPath.number", "Number must be nonnegative")
                    }
                    MatchParticipantKind.UNKNOWN -> check(participant.firstName == null && participant.lastName == null && participant.number == null,
                        participantPath, "UNKNOWN cannot have a name or number")
                }
            }
        }
        check(match.endedAt == null || (match.startedAt != null && !match.endedAt.isBefore(match.startedAt)),
            "$path.endedAt", "End requires a start and cannot precede it")
        check(match.startedAt != null || match.events.isEmpty(), "$path.events", "PLANNED match cannot contain events")
        val teamByParticipant = match.teams.flatMap { team -> team.participants.map { it.participantId to team.teamId } }.toMap()
        val slots = validateLineupEntries(match, "$path.pointLineups", teamByParticipant.keys)
        val teamByActor = teamByParticipant + slots
        var lastSequence = 0
        var lastTime = match.startedAt
        match.events.forEachIndexed { e, event ->
            val eventPath = "$path.events[$e]"
            check(event.sequenceNumber > lastSequence, "$eventPath.sequenceNumber", "Sequence numbers must be positive and strictly increasing")
            check(lastTime == null || !event.occurredAt.isBefore(lastTime), "$eventPath.occurredAt", "Event precedes match start or previous event")
            check(match.endedAt == null || !event.occurredAt.isAfter(match.endedAt), "$eventPath.occurredAt", "Event occurs after match end")
            validateEvent(event, eventPath, teamByActor, match.teams.map { it.teamId })
            lastSequence = event.sequenceNumber
            lastTime = event.occurredAt
        }
        validateAnnouncements(match, "$path.announcements")
        val storedEvents = match.events.map { StoredEvent(it.id, it.sequenceNumber, it.toDomain()) }
        val decision = sequencePolicy.validate(storedEvents.map { it.event }, requirePointEnded = match.endedAt != null)
        if (decision is EventSequenceDecision.Rejected) {
            fail("$path.events[${decision.violation.eventIndex}]", "Invalid event sequence in state ${decision.violation.currentState}")
        }
        if (match.pointLineups.isNotEmpty() || match.containsSubstitutions()) {
            validateLineupScope(match, path, slots.keys, storedEvents, teamByParticipant)
        }
    }

    private fun validateLineupEntries(match: PortableMatch, path: String, rosterIds: Set<UUID>): Map<UUID, UUID> {
        if (match.pointLineups.isEmpty()) return emptyMap()
        val playersByTeam = match.teams.associate { team ->
            team.teamId to team.participants.filter { it.kind == MatchParticipantKind.PLAYER }.map { it.participantId }.toSet()
        }
        val slots = mutableMapOf<UUID, UUID>()
        val introductionIds = mutableSetOf<UUID>()
        match.pointLineups.forEachIndexed { pointIndex, lineup ->
            val pointPath = "$path[$pointIndex]"
            check(lineup.teams.size == playersByTeam.size && lineup.teams.map { it.teamId }.toSet() == playersByTeam.keys,
                "$pointPath.teams", "Both match teams must appear once")
            val numbersByTeam = playersByTeam.keys.associateWith { mutableSetOf<Int>() }
            val playersUsedByTeam = playersByTeam.keys.associateWith { mutableSetOf<UUID>() }
            lineup.teams.forEachIndexed { teamIndex, team ->
                val teamPath = "$pointPath.teams[$teamIndex]"
                check(team.entries.size <= match.playersPerTeam, "$teamPath.entries", "Lineup exceeds team capacity")
                val numbers = numbersByTeam.getValue(team.teamId)
                val usedPlayers = playersUsedByTeam.getValue(team.teamId)
                val roster = playersByTeam.getValue(team.teamId)
                team.entries.forEachIndexed { entryIndex, entry ->
                    val entryPath = "$teamPath.entries[$entryIndex]"
                    val playerId = when (entry) {
                        is PortablePointLineupPlayer -> entry.participantId
                        is PortablePointLineupTemporary -> {
                            validateSlotId(entry.slotId, team.teamId, rosterIds, slots, entryPath)
                            check(entry.number in 1..match.playersPerTeam && numbers.add(entry.number),
                                "$entryPath.number", "Slot numbers must be unique within the team and within capacity")
                            entry.resolvedParticipantId
                        }
                    }
                    check(playerId == null || (playerId in roster && usedPlayers.add(playerId)), entryPath,
                        "Player must be an available PLAYER from the same match team")
                }
            }
            lineup.substitutionSlots.orEmpty().forEachIndexed { index, slot ->
                val slotPath = "$pointPath.substitutionSlots[$index]"
                check(slot.teamId in playersByTeam, "$slotPath.teamId", "Team must belong to this match")
                validateSlotId(slot.slotId, slot.teamId, rosterIds, slots, slotPath)
                check(slot.number > 0 && numbersByTeam.getValue(slot.teamId).add(slot.number), "$slotPath.number",
                    "Slot numbers must be positive and unique within the point team")
                check(introductionIds.add(slot.introducedByEventId), "$slotPath.introducedByEventId",
                    "An event can introduce only one slot")
                slot.resolvedParticipantId?.let { player ->
                    check(player in playersByTeam.getValue(slot.teamId) && playersUsedByTeam.getValue(slot.teamId).add(player),
                        "$slotPath.resolvedParticipantId", "Player must be an available PLAYER from the same match team")
                }
            }
        }
        return slots
    }

    private fun validateSlotId(
        slotId: UUID, teamId: UUID, rosterIds: Set<UUID>, slots: MutableMap<UUID, UUID>, path: String,
    ) {
        check(slotId !in rosterIds && slots.putIfAbsent(slotId, teamId) == null,
            "$path.slotId", "Slot ID must be unique and distinct from match participants")
    }

    private fun validateLineupScope(
        match: PortableMatch, path: String, slots: Set<UUID>, events: List<StoredEvent>, teamByParticipant: Map<UUID, UUID>,
    ) {
        val timeline = PointTimeline.from(match.id, match.status, events)
        val pointsByOpening = timeline.associateBy { it.openingEventId }
        val lineupsByOpening = mutableMapOf<UUID?, PointLineup>()
        val hasSubstitutions = match.containsSubstitutions()
        val eventsByPoint = if (hasSubstitutions) PointTimeline.eventsByPoint(timeline, events) else emptyMap()
        val introduced = mutableListOf<IntroducedPointSlot>()
        match.pointLineups.forEachIndexed { index, lineup ->
            val opening = lineup.openingEventId
            check(opening in pointsByOpening && opening !in lineupsByOpening,
                "$path.pointLineups[$index].openingEventId", "Expected a distinct canonical point boundary")
            lineupsByOpening[opening] = PointLineup(revision = 1, teams = lineup.teams.map { team ->
                PointLineupTeam(team.teamId, team.entries.map { entry ->
                    when (entry) {
                        is PortablePointLineupPlayer -> PointLineupPlayer(entry.participantId)
                        is PortablePointLineupTemporary -> PointLineupTemporary(entry.slotId, entry.number, entry.resolvedParticipantId)
                    }
                })
            })
            val pointId = pointsByOpening.getValue(opening).pointId
            introduced += validateIntroductions(lineup, pointId, eventsByPoint[pointId].orEmpty(), "$path.pointLineups[$index]")
        }
        val membersByOpening = lineupsByOpening.mapValues { (_, lineup) ->
            lineup.teams.flatMap { team -> team.entries.map { it.actorId } }.toSet()
        }
        val unknowns = match.teams.flatMap { it.participants }
            .filter { it.kind == MatchParticipantKind.UNKNOWN }.map { it.participantId }.toSet()
        val replayedOpenings = if (hasSubstitutions) {
            val lineupsByPoint = lineupsByOpening.mapKeys { (opening, _) -> pointsByOpening.getValue(opening).pointId }
            val confirmed = ConfirmedJournal(lineupsByPoint, unknowns, teamByParticipant - unknowns,
                writesEnabled = true, introducedSlots = introduced)
            timeline.filter { point ->
                val pointEvents = eventsByPoint[point.pointId].orEmpty()
                if (pointEvents.none { it.event.type == EventType.PLAYER_SUBSTITUTION }) return@filter false
                try {
                    confirmed.validateSubstitutionPoint(point.pointId, pointEvents)
                } catch (error: PointLineupRejectedException) {
                    fail("$path.events", "Point ${point.number}: ${error.message}")
                }
                true
            }.map { it.openingEventId }.toSet()
        } else emptySet()
        val closings = timeline.mapNotNull { it.closingEventId }.toSet()
        var opening: UUID? = null
        match.events.forEachIndexed { index, event ->
            if (opening !in replayedOpenings) {
                val members = membersByOpening[opening]
                for (actor in listOfNotNull(event.fromParticipantId, event.toParticipantId)) {
                    check(if (members == null) actor !in slots else actor in members || actor in unknowns,
                        "$path.events[$index]", "Event actor must belong to its point lineup or be a match-wide UNKNOWN participant")
                }
            }
            if (event.id in closings) opening = event.id
        }
    }

    private fun validateIntroductions(
        lineup: PortablePointLineup, pointId: UUID, events: List<StoredEvent>, path: String,
    ): List<IntroducedPointSlot> {
        val slots = lineup.substitutionSlots.orEmpty()
        if (slots.isEmpty()) return emptyList()
        val substitutions = events.filter { it.event.type == EventType.PLAYER_SUBSTITUTION }
            .associate { it.id to (it.event as TwoPlayerEvent) }
        val incomingActors = substitutions.values.map { it.toParticipant }.toSet()
        val mapped = slots.mapIndexed { index, slot ->
            val slotPath = "$path.substitutionSlots[$index]"
            check(substitutions[slot.introducedByEventId]?.toParticipant == slot.slotId, "$slotPath.introducedByEventId",
                "Expected the active substitution introducing this slot in the same point")
            check(slot.resolvedParticipantId !in incomingActors, "$slotPath.resolvedParticipantId",
                "Player already occupied a place in this point through a substitution")
            IntroducedPointSlot(pointId, slot.toDomain())
        }
        val maxNumberByTeam = lineup.teams.associate { team ->
            team.teamId to (team.entries.filterIsInstance<PortablePointLineupTemporary>().maxOfOrNull { it.number } ?: 0)
        }.toMutableMap()
        val slotsByEvent = slots.associateBy { it.introducedByEventId }
        for (eventId in substitutions.keys) {
            val slot = slotsByEvent[eventId] ?: continue
            // Omitted soft-deleted introductions can leave gaps in the allocation.
            check(slot.number > maxNumberByTeam.getValue(slot.teamId), "$path.substitutionSlots",
                "Slot numbers must increase in introduction order within the point team")
            maxNumberByTeam[slot.teamId] = slot.number
        }
        return mapped
    }

    private fun validateAnnouncements(match: PortableMatch, path: String) {
        unique(match.announcements.map { it.id }, path)
        val teamIds = match.teams.map { it.teamId }
        val ordered = match.announcements.withIndex().sortedWith(
            compareBy<IndexedValue<PortableAnnouncement>> { it.value.startedAt }
                .thenBy { it.value.endedAt != it.value.startedAt },
        )
        var previousEnd = match.startedAt
        for ((index, interval) in ordered) {
            val intervalPath = "$path[$index]"
            check(interval.teamId == null || interval.teamId in teamIds, "$intervalPath.teamId",
                "Team must belong to this match")
            check(previousEnd != null && !interval.startedAt.isBefore(previousEnd), "$intervalPath.startedAt",
                "Start requires an active match and cannot overlap another interval or precede match start")
            val end = interval.endedAt
            check(end == null || !end.isBefore(interval.startedAt), "$intervalPath.endedAt",
                "End cannot precede start")
            check(match.endedAt == null || (end != null && !end.isAfter(match.endedAt)), "$intervalPath.endedAt",
                "Finished matches require closed intervals ending no later than match end")
            previousEnd = end
        }
    }

    private fun validateEvent(event: PortableEvent, path: String, teams: Map<UUID, UUID>, teamIds: List<UUID>) {
        when (event.type.category) {
            EventCategory.ONE_PLAYER -> check(event.fromParticipantId in teams && event.toParticipantId == null && event.teamId == null,
                path, "ONE_PLAYER requires only fromParticipantId belonging to this match")
            EventCategory.TWO_PLAYER -> {
                check(event.fromParticipantId in teams && event.toParticipantId in teams && event.teamId == null && event.fromParticipantId != event.toParticipantId,
                    path, "TWO_PLAYER requires two distinct match participants and no teamId")
                val sameTeam = teams[event.fromParticipantId] == teams[event.toParticipantId]
                val requiresSameTeam = event.type in setOf(EventType.PASS, EventType.GOAL, EventType.PLAYER_SUBSTITUTION)
                check(sameTeam == requiresSameTeam, path,
                    "PASS, GOAL and PLAYER_SUBSTITUTION require the same team; blocks, interceptions and callahans require opposing teams")
            }
            EventCategory.TEAM -> check(event.teamId in teamIds && event.fromParticipantId == null && event.toParticipantId == null,
                path, "TEAM requires only teamId belonging to this match")
            EventCategory.ANNOUNCEMENT -> check(
                (event.teamId == null || event.teamId in teamIds) && event.fromParticipantId == null && event.toParticipantId == null,
                path, "Announcement start accepts only an optional teamId belonging to this match",
            )
            EventCategory.SYSTEM -> check(event.teamId == null && event.fromParticipantId == null && event.toParticipantId == null,
                path, "SYSTEM cannot reference teams or participants")
        }
    }

    private fun unique(ids: List<UUID>, path: String) = check(ids.size == ids.distinct().size, path, "Duplicate IDs")
    private fun text(value: String?, path: String, max: Int) = check(value != null && value.isNotBlank() && value.length <= max,
        path, "Nonblank text of at most $max characters is required")
    private fun check(valid: Boolean, path: String, detail: String) { if (!valid) fail(path, detail) }
    private fun fail(path: String, detail: String): Nothing = throw InvalidMatchTransferException(path, detail)
}

private fun PortableMatch.containsSubstitutions() = events.any { it.type == EventType.PLAYER_SUBSTITUTION }

internal fun List<PortableMatch>.containsSubstitutions() = any { it.containsSubstitutions() }

internal fun List<PortableMatch>.containsIncomingReserves() =
    any { match -> match.pointLineups.any { !it.substitutionSlots.isNullOrEmpty() } }

internal fun List<PortableMatch>.requiredFormatVersion() = when {
    containsIncomingReserves() -> 6
    containsSubstitutions() -> 5
    any { it.pointLineups.isNotEmpty() } -> 4
    containsPossessionChanges() -> 3
    any { it.announcements.isNotEmpty() } -> 2
    else -> 1
}

internal fun List<PortableMatch>.containsPossessionChanges() =
    any { match -> match.events.any { it.type == EventType.POSSESSION_SET } }

internal val PortableMatch.status: MatchStatus
    get() = when {
        endedAt != null -> MatchStatus.FINISHED
        startedAt != null -> MatchStatus.IN_PROGRESS
        else -> MatchStatus.PLANNED
    }

internal fun PortableEvent.toDomain(): Event = when (type.category) {
    EventCategory.ONE_PLAYER -> OnePlayerEvent(requireNotNull(fromParticipantId), occurredAt, type)
    EventCategory.TWO_PLAYER -> TwoPlayerEvent(requireNotNull(fromParticipantId), requireNotNull(toParticipantId), occurredAt, type)
    EventCategory.TEAM -> TeamEvent(requireNotNull(teamId), occurredAt, type)
    EventCategory.ANNOUNCEMENT -> AnnouncementStartEvent(teamId, occurredAt)
    EventCategory.SYSTEM -> SystemEvent(occurredAt, type)
}
