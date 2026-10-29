package com.github.mihanizzm.ultistats.transfer

import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.validation.event.EventSequenceDecision
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import org.springframework.stereotype.Component
import java.util.UUID

/** Validates the entire graph without accessing or modifying the destination database. */
@Component
class MatchTransferValidator(private val sequencePolicy: EventSequencePolicy) {
    fun validate(data: MatchTransferPackage) {
        check(data.formatVersion == 1, "formatVersion", "Only format version 1 is supported")
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
        var lastSequence = 0
        var lastTime = match.startedAt
        match.events.forEachIndexed { e, event ->
            val eventPath = "$path.events[$e]"
            check(event.sequenceNumber > lastSequence, "$eventPath.sequenceNumber", "Sequence numbers must be positive and strictly increasing")
            check(lastTime == null || !event.occurredAt.isBefore(lastTime), "$eventPath.occurredAt", "Event precedes match start or previous event")
            check(match.endedAt == null || !event.occurredAt.isAfter(match.endedAt), "$eventPath.occurredAt", "Event occurs after match end")
            validateEvent(event, eventPath, teamByParticipant, match.teams.map { it.teamId })
            lastSequence = event.sequenceNumber
            lastTime = event.occurredAt
        }
        val decision = sequencePolicy.validate(match.events.map { it.toDomain() }, requirePointEnded = match.endedAt != null)
        if (decision is EventSequenceDecision.Rejected) {
            fail("$path.events[${decision.violation.eventIndex}]", "Invalid event sequence in state ${decision.violation.currentState}")
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
                check(sameTeam == (event.type == EventType.PASS || event.type == EventType.GOAL), path,
                    "PASS/GOAL require the same team; blocks, interceptions and callahans require opposing teams")
            }
            EventCategory.TEAM -> check(event.teamId in teamIds && event.fromParticipantId == null && event.toParticipantId == null,
                path, "TEAM requires only teamId belonging to this match")
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

internal fun PortableEvent.toDomain(): Event = when (type.category) {
    EventCategory.ONE_PLAYER -> OnePlayerEvent(requireNotNull(fromParticipantId), occurredAt, type)
    EventCategory.TWO_PLAYER -> TwoPlayerEvent(requireNotNull(fromParticipantId), requireNotNull(toParticipantId), occurredAt, type)
    EventCategory.TEAM -> TeamEvent(requireNotNull(teamId), occurredAt, type)
    EventCategory.SYSTEM -> SystemEvent(occurredAt, type)
}
