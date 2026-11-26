package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.dto.response.OnePlayerEventResponse
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnostic
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnosticCode
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDiagnosticCode.*
import com.github.mihanizzm.ultistats.dto.response.PointCompletionPreviewEntry
import com.github.mihanizzm.ultistats.dto.response.TwoPlayerEventResponse
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipant
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.EventType.*
import com.github.mihanizzm.ultistats.model.events.actorId
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.scoringTeam
import com.github.mihanizzm.ultistats.repository.jpa.IntroducedPointSlot
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupSlotActor
import com.github.mihanizzm.ultistats.validation.event.EventSequencePolicy
import com.github.mihanizzm.ultistats.validation.event.EventSequenceState
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class CompletionPoint(
    val match: Match,
    val pointId: UUID,
    val marker: StoredEvent,
    val source: List<StoredEvent>,
    val firstPoint: Boolean,
    val priorActionTime: Instant?,
    val nextActionTime: Instant?,
    val previousScoringTeam: UUID?,
    val lineup: PointLineup?,
    val roster: List<MatchParticipant>,
    val introduced: Map<UUID, PointSubstitutionSlot>,
    val actorTeams: Map<UUID, UUID>,
)

internal fun completionPoint(
    match: Match, point: PointBoundary, journal: List<StoredEvent>, roster: List<MatchParticipant>,
    lineup: PointLineup?, introduced: List<IntroducedPointSlot>, slots: Map<UUID, PointLineupSlotActor>,
): CompletionPoint? {
    val opening = journal.indexOfFirst { it.id == point.openingEventId }
    val closing = journal.indexOfFirst { it.id == point.closingEventId }
    val marker = journal.getOrNull(closing)?.takeIf { it.event.type == POINT_RESULT } ?: return null
    val source = journal.subList(opening + 1, closing)
    val sourceIds = source.mapTo(mutableSetOf()) { it.id }
    val pointSlots = introduced.filter { it.pointId == point.pointId && it.slot.introducedByEventId in sourceIds }
        .associate { it.slot.slotId to it.slot }
    val rosterTeams = roster.associate { it.participantId to it.teamId }
    val starters = lineup?.teams.orEmpty().flatMap { team ->
        team.entries.filterIsInstance<PointLineupTemporary>().map { it.slotId to team.teamId }
    }.toMap()
    val previousScore = journal.getOrNull(opening)?.event?.scoringTeam { rosterTeams[it] ?: slots[it]?.teamId }
    val before = journal.subList(0, opening + 1).lastOrNull { it.event.type != POINT_RESULT }
    val after = journal.subList(closing + 1, journal.size).firstOrNull { it.event.type != POINT_RESULT }
    return CompletionPoint(
        match, point.pointId, marker, source, opening < 0, before?.event?.occurredAt, after?.event?.occurredAt, previousScore,
        lineup, roster, pointSlots, rosterTeams + starters + pointSlots.mapValues { it.value.teamId },
    )
}

internal fun completionPreview(point: CompletionPoint, grouped: Map<UUID?, List<PointCompletionInsertion>>) = buildList {
    grouped[null].orEmpty().forEach { add(PointCompletionPreviewEntry(insertion = it)) }
    for (event in point.source) {
        add(PointCompletionPreviewEntry(original = EventResponse.from(event)))
        grouped[event.id].orEmpty().forEach { add(PointCompletionPreviewEntry(insertion = it)) }
    }
}

internal fun validateCompletionPreview(
    policy: EventSequencePolicy,
    point: CompletionPoint,
    scoringTeam: UUID,
    preview: List<PointCompletionPreviewEntry>,
): List<PointCompletionDiagnostic> = buildList {
    val startingAt = requireNotNull(point.match.effectiveStartedAt)
    val actionClocks = preview.map {
        it.original?.let { event -> Duration.between(startingAt, event.occurredAt) }
            ?: it.insertion?.elapsedOffsetMs?.let(Duration::ofMillis)
    }
    // POINT_RESULT registration times are never used as gameplay time bounds.
    val end = Duration.between(startingAt, requireNotNull(point.match.effectiveEndedAt))
    var upperBound = point.nextActionTime?.let { Duration.between(startingAt, it) } ?: end
    val nextRecordedClocks = preview.indices.reversed().map { index ->
        val next = upperBound
        if (preview[index].original != null) upperBound = requireNotNull(actionClocks[index])
        next
    }.asReversed()
    var previousClock = point.priorActionTime?.let { Duration.between(startingAt, it) } ?: Duration.ZERO

    var state: EventSequenceState = if (point.firstPoint) EventSequenceState.BeforeFirstPull else EventSequenceState.PointEnded
    var ended = false
    var puller: UUID? = null
    var receivingAfterPullFrom: UUID? = null
    var owner: UUID? = null
    val unknown = point.roster.filter { it.kind == MatchParticipantKind.UNKNOWN }.map { it.participantId }.toSet()
    val rosterPlayers = point.roster.filter { it.kind == MatchParticipantKind.PLAYER }.map { it.participantId }.toSet()
    val startingEntries = point.lineup?.teams?.flatMap { it.entries }
    val field = startingEntries?.map { it.actorId }?.toMutableSet()
    val identified = startingEntries.orEmpty().filterIsInstance<PointLineupTemporary>()
        .mapNotNull { it.resolvedParticipantId }.toMutableSet()
    val previousWinner = point.previousScoringTeam.takeUnless { point.source.any { e -> e.event.type == HALFTIME_END } }

    for ((index, entry) in preview.withIndex()) {
        val action = entry.action()
        val (id, type, from, to) = action
        fun report(code: PointCompletionDiagnosticCode, detail: String) = add(PointCompletionDiagnostic(code, detail, id))

        val elapsed = actionClocks[index]
        if (entry.original != null) {
            previousClock = requireNotNull(elapsed)
        } else if (elapsed != null) {
            if (elapsed < Duration.ZERO || elapsed > end) {
                report(CLOCK_OUT_OF_BOUNDS, "Elapsed time must be between match start and end")
            } else if (elapsed < previousClock || elapsed > nextRecordedClocks[index]) {
                report(CLOCK_OUT_OF_ORDER, "Elapsed time falls outside the neighboring known times")
            } else {
                previousClock = elapsed
            }
        }

        val nextState = policy.transition(state, type)
        if (ended || nextState == null) {
            report(EVENT_ORDER, "${type.name} cannot follow the preceding candidate actions")
        } else {
            state = nextState
        }
        val fromTeam = point.actorTeams[from]
        val toTeam = point.actorTeams[to]
        if ((from != null && fromTeam == null) || (to != null && toTeam == null)) {
            report(ACTOR_UNAVAILABLE, "An actor is not a match participant or an active slot of this point")
        }
        if (to != null && fromTeam != null && toTeam != null &&
            (type in sameTeamActions) != (fromTeam == toTeam)) {
            report(ACTOR_TEAM_CONFLICT, "Actors must respect the team relationship for ${type.name}")
        }

        if (type == PLAYER_SUBSTITUTION) {
            val reserve = point.introduced[to]
            val validIncoming = if (reserve != null) {
                reserve.introducedByEventId == id && reserve.teamId == fromTeam
            } else {
                to in rosterPlayers && to !in identified && fromTeam != null && fromTeam == toTeam
            }
            if (field == null || from !in field || to in field || !validIncoming) {
                report(ACTOR_NOT_ON_FIELD, "A substitution requires the outgoing actor on the field and an eligible incoming teammate")
            } else {
                field.remove(from)
                field.add(requireNotNull(to))
                reserve?.resolvedParticipantId?.let(identified::add)
                if (owner == from) owner = to
            }
        } else if (field != null && !(type == BRICK && from == puller) &&
            listOfNotNull(from, to).any { it !in field && it !in unknown }) {
            report(ACTOR_NOT_ON_FIELD, "An actor is absent from the effective lineup at this position")
        }

        when (type) {
            PULL -> {
                puller = from
                owner = null
                receivingAfterPullFrom = fromTeam
                if (previousWinner != null && fromTeam != previousWinner) {
                    report(ACTOR_TEAM_CONFLICT, "The pulling team differs from the previous scoring team")
                }
            }
            BRICK -> if (from != puller) report(POSSESSION_CONFLICT, "BRICK must name the puller")
            PICKUP -> {
                if (fromTeam != null && fromTeam == receivingAfterPullFrom) {
                    report(ACTOR_TEAM_CONFLICT, "The receiving team has the first pickup after the pull")
                }
                owner = from
                receivingAfterPullFrom = null
            }
            POSSESSION_SET -> {
                owner = from
                receivingAfterPullFrom = null
            }
            PASS, GOAL, CALLAHAN, INTERCEPTION, INCOMPLETE_PASS, BLOCK, BLOCK_MARKER, BLOCK_FIELD -> {
                if (owner != from) report(POSSESSION_CONFLICT, "The first actor differs from the current disc holder")
                owner = if (type in touchesReceived) to else null
                if (type.closesPoint) {
                    ended = true
                    if (toTeam != scoringTeam) {
                        report(SCORING_TEAM_CONFLICT, "The personal result differs from the canonical scoring team")
                    }
                }
            }
            else -> Unit
        }
    }
    if (puller == null) add(PointCompletionDiagnostic(MISSING_PULL, "Add the point's pull"))
    if (!ended) add(PointCompletionDiagnostic(MISSING_PERSONAL_RESULT, "Add a goal or Callahan for the recorded scoring team"))
}

private val sameTeamActions = setOf(PASS, GOAL, PLAYER_SUBSTITUTION)
private val touchesReceived = setOf(PASS, GOAL, INTERCEPTION, CALLAHAN)

private data class PreviewAction(val id: UUID, val type: EventType, val from: UUID? = null, val to: UUID? = null)

private fun PointCompletionPreviewEntry.action(): PreviewAction {
    insertion?.let { return PreviewAction(it.id, it.type.eventType(), it.fromActorId, it.toActorId) }
    val event = requireNotNull(original)
    return when (event) {
        is OnePlayerEventResponse -> PreviewAction(event.id, event.type, event.participantId)
        is TwoPlayerEventResponse -> PreviewAction(event.id, event.type, event.fromParticipantId, event.toParticipantId)
        else -> PreviewAction(event.id, event.type)
    }
}
