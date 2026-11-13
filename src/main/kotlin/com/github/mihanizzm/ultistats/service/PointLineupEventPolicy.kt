package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.events.actorId
import com.github.mihanizzm.ultistats.model.events.afterSubstitution
import com.github.mihanizzm.ultistats.model.events.substitutions
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.IntroducedPointSlot
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class PointLineupEventPolicy(
    private val lineups: PointLineupRepository,
    private val participants: SpringDataMatchParticipantRepository,
    private val events: SpringDataEventRepository,
    @Value("\${ultistats.point-lineups.write-enabled:false}") private val writesEnabled: Boolean,
    @Value("\${ultistats.point-substitutions.write-enabled:false}") private val substitutionsEnabled: Boolean,
    @Value("\${ultistats.point-incoming-reserves.write-enabled:false}") private val incomingReservesEnabled: Boolean,
) {
    val incomingReservesWriteEnabled: Boolean get() = substitutionsWriteEnabled && incomingReservesEnabled
    val substitutionsWriteEnabled: Boolean get() = writesEnabled && substitutionsEnabled

    fun requireWriteClient(matchId: UUID, client: PointLineupClient) {
        requireSubstitutionReadClient(matchId, client)
        if (client == PointLineupClient.LEGACY && lineups.exists(matchId)) requireClient()
    }

    fun requireSubstitutionReadClient(matchId: UUID, client: PointLineupClient) {
        if (client != PointLineupClient.INCOMING_RESERVES && lineups.hasActiveIntroducedSlots(matchId)) {
            throw incomingReserveClientRequired()
        }
        if (!client.supportsSubstitutions && events.existsByMatchIdAndEventTypeAndDeletedAtIsNull(matchId, EventType.PLAYER_SUBSTITUTION)) {
            throw substitutionClientRequired()
        }
    }

    fun requireReadClient(matchId: UUID, events: Iterable<EventEntity>, client: PointLineupClient) {
        requireSubstitutionReadClient(matchId, client)
        if (client == PointLineupClient.LEGACY && events.any { it.fromSlotId != null || it.toSlotId != null }) requireClient()
    }

    fun incomingReserveClientRequired() = PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CLIENT_REQUIRED, "This journal requires incoming reserve support (capability 3)",
    )

    fun substitutionClientRequired() = PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CLIENT_REQUIRED, "This journal requires point substitution support (capability 2)",
    )

    /** Write callers obtain this view while holding the match row lock. */
    fun journal(matchId: UUID): ConfirmedJournal? {
        if (!lineups.exists(matchId)) return null
        val roster = participants.findAllByMatchId(matchId)
        return ConfirmedJournal(
            lineups.list(matchId, roster.map { it.teamId }.distinct()),
            roster.filter { it.kind == MatchParticipantKind.UNKNOWN }.map { it.participantId }.toSet(),
            roster.filter { it.kind == MatchParticipantKind.PLAYER }.associate { it.participantId to it.teamId },
            writesEnabled,
            lineups.introducedSlots(matchId),
        )
    }

    fun insertIncomingSlots(matchId: UUID, slots: List<IntroducedPointSlot>) {
        slots.forEach { lineups.insertIncomingSlot(matchId, it) }
    }

    fun bindIntroductions(matchId: UUID, slots: List<IntroducedPointSlot>) {
        slots.forEach { lineups.bindIntroduction(matchId, it) }
    }

    private fun requireClient(): Nothing = throw PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CLIENT_REQUIRED, "This journal requires a point-lineup-aware client",
    )
}

class ConfirmedJournal(
    private val lineups: Map<UUID, PointLineup>,
    private val unknownIds: Set<UUID>,
    private val rosterTeams: Map<UUID, UUID>,
    private val writesEnabled: Boolean,
    introducedSlots: List<IntroducedPointSlot> = emptyList(),
) {
    private val introduced = introducedSlots.associateByTo(mutableMapOf()) { it.slot.slotId }
    private val rosterIds = rosterTeams.keys + unknownIds
    private val entriesByPoint = lineups.mapValues { (_, lineup) -> lineup.teams.flatMap { it.entries } }
    private val slotPoints = mutableMapOf<UUID, UUID>().apply {
        introducedSlots.forEach { put(it.slot.slotId, it.pointId) }
        for ((pointId, actors) in entriesByPoint) {
            actors.filterIsInstance<PointLineupTemporary>().forEach { put(it.slotId, pointId) }
        }
    }
    val slotIds: Set<UUID> get() = slotPoints.keys

    fun validateWrite(
        match: Match, prefix: List<StoredEvent>, event: Event, append: Boolean, introductionId: UUID? = null,
    ): IntroducedPointSlot? {
        val actors = event.actors()
        if (actors.isEmpty()) return null
        val hasSlot = actors.any(slotIds::contains)
        if (hasSlot && !writesEnabled) {
            throw PointLineupRejectedException(
                PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled",
            )
        }
        // Use the open point of this prefix even when editing a finished match.
        val points = PointTimeline.from(match.id, MatchStatus.IN_PROGRESS, prefix)
        val point = points.last()
        val initial = lineups[point.pointId]?.teams
        if (initial == null) {
            if (event.type == EventType.PLAYER_SUBSTITUTION) substitutionConflict("Confirm the active point before substituting")
            if (append && event.type == EventType.PULL) conflict("Confirm the next point lineup before recording PULL")
            if (hasSlot) conflict("Temporary actors must belong to the current confirmed point")
            return null
        }

        val pointEvents = prefix.asReversed().takeWhile { it.id != point.openingEventId }.asReversed()
        val replacements = pointEvents.substitutions()
        val replayedIntroductions = pointEvents.map { it.id }.toSet()
        val previousPuller = (pointEvents.firstOrNull { it.id == point.pullEventId }?.event as? OnePlayerEvent)?.participant
            ?.takeIf { id -> replacements.any { it.fromParticipant == id } }
        val effective = replacements.fold(initial) { teams, replacement -> teams.applyReplacement(replacement) }
        if (event.type == EventType.PLAYER_SUBSTITUTION) {
            if (point.status != PointStatus.ACTIVE) substitutionConflict("A substitution requires an active point after PULL")
            val substitution = event as TwoPlayerEvent
            if (introductionId != null) return provisionReserve(point.pointId, initial, effective, substitution, introductionId)
            validateSubstitution(point.pointId, initial, effective, substitution, replayedIntroductions)
        } else if (!actorsAllowed(effective, event, previousPuller)) {
            if (replacements.isNotEmpty()) substitutionConflict("Event actors must belong to the effective point lineup")
            conflict("Event actors must belong to the current confirmed point or be match-wide UNKNOWN participants")
        }
        return null
    }

    private fun provisionReserve(
        pointId: UUID, initial: List<PointLineupTeam>, effective: List<PointLineupTeam>,
        event: TwoPlayerEvent, introductionId: UUID,
    ): IntroducedPointSlot {
        val outgoing = outgoingTeam(effective, event)
        val incoming = event.toParticipant
        if (incoming in rosterIds || incoming in slotIds) substitutionConflict("Temporary slot ID is already used in this match")
        val originalMax = initial.first { it.teamId == outgoing.teamId }.entries
            .filterIsInstance<PointLineupTemporary>().maxOfOrNull { it.number } ?: 0
        val introducedMax = introduced.values.filter { it.pointId == pointId && it.slot.teamId == outgoing.teamId }
            .maxOfOrNull { it.slot.number } ?: 0
        val maxNumber = maxOf(originalMax, introducedMax)
        if (maxNumber == Int.MAX_VALUE) substitutionConflict("No further temporary slot numbers are available for this point team")
        return IntroducedPointSlot(pointId, PointSubstitutionSlot(
            outgoing.teamId, incoming, maxNumber + 1, null, introductionId,
        )).also {
            introduced[incoming] = it
            slotPoints[incoming] = pointId
        }
    }

    private fun List<PointLineupTeam>.applyReplacement(event: TwoPlayerEvent): List<PointLineupTeam> =
        introduced[event.toParticipant]?.let { afterSubstitution(event, it.slot.asTemporary()) } ?: afterSubstitution(event)

    /** Replay only points whose eligibility is affected by a substitution in either journal. */
    fun validateSubstitutionEdit(match: Match, original: List<StoredEvent>, proposed: List<StoredEvent>) {
        if (original.none { it.event.type == EventType.PLAYER_SUBSTITUTION }) return
        fun groups(journal: List<StoredEvent>) =
            PointTimeline.eventsByPoint(PointTimeline.from(match.id, MatchStatus.IN_PROGRESS, journal), journal)
        val originalSubstitutions = buildMap {
            for ((pointId, journal) in groups(original)) {
                journal.filter { it.event.type == EventType.PLAYER_SUBSTITUTION }.forEach { put(it.id, pointId) }
            }
        }
        val affectedPoints = originalSubstitutions.values.toSet()
        for ((pointId, journal) in groups(proposed)) {
            val replacements = journal.filter { it.event.type == EventType.PLAYER_SUBSTITUTION }
            if (replacements.any { originalSubstitutions[it.id] != pointId }) {
                substitutionConflict("An edit cannot move a substitution to a different point")
            }
            if (pointId in affectedPoints) validateSubstitutionPoint(pointId, journal)
        }
    }

    /** Replay a point affected by a substitution using the original confirmed lineup. */
    internal fun validateSubstitutionPoint(pointId: UUID, journal: List<StoredEvent>) {
        val initial = lineups[pointId]?.teams ?: substitutionConflict("Substitutions require a confirmed point")
        var effective = initial
        var puller: UUID? = null
        val replayedIntroductions = mutableSetOf<UUID>()
        for ((id, _, event) in journal) {
            if (event.type == EventType.PULL) puller = (event as OnePlayerEvent).participant
            if (event.type == EventType.PLAYER_SUBSTITUTION) {
                if (puller == null) substitutionConflict("A substitution requires an active point after PULL")
                val substitution = event as TwoPlayerEvent
                val incoming = introduced[substitution.toParticipant]
                if (incoming == null) {
                    validateSubstitution(pointId, initial, effective, substitution, replayedIntroductions)
                } else if (incoming.pointId != pointId || incoming.slot.introducedByEventId != id ||
                    incoming.slot.teamId != outgoingTeam(effective, substitution).teamId) {
                    substitutionConflict("An incoming reserve must be introduced by its original event and point team")
                }
                effective = effective.applyReplacement(substitution)
                replayedIntroductions.add(id)
            } else if (!actorsAllowed(effective, event, puller)) {
                substitutionConflict("Event actors must belong to the effective point lineup")
            }
        }
    }

    private fun outgoingTeam(effective: List<PointLineupTeam>, event: TwoPlayerEvent): PointLineupTeam =
        effective.firstOrNull { team -> team.entries.any { it.actorId == event.fromParticipant } }
            ?: substitutionConflict("The outgoing player or temporary slot must be on the field")

    private fun validateSubstitution(
        pointId: UUID, initial: List<PointLineupTeam>, effective: List<PointLineupTeam>, event: TwoPlayerEvent,
        replayedIntroductions: Set<UUID>,
    ) {
        val outgoing = outgoingTeam(effective, event)
        val incoming = event.toParticipant
        if (rosterTeams[incoming] != outgoing.teamId || outgoing.entries.any { it.actorId == incoming }) {
            substitutionConflict("The incoming player must be a roster player of the same team who is off the field")
        }
        if (initial.flatMap { it.entries }.filterIsInstance<PointLineupTemporary>().any { it.resolvedParticipantId == incoming } ||
            introduced.values.any { it.pointId == pointId && it.slot.resolvedParticipantId == incoming && it.slot.introducedByEventId in replayedIntroductions }) {
            substitutionConflict("The incoming player is already identified as a temporary actor in this point")
        }
    }

    private fun actorsAllowed(teams: List<PointLineupTeam>, event: Event, puller: UUID?): Boolean {
        // BRICK attributes the preceding PULL even when that puller has since left the field.
        if (event.type == EventType.BRICK && (event as OnePlayerEvent).participant == puller) return true
        return event.actors().all { id -> id in unknownIds || teams.any { team -> team.entries.any { it.actorId == id } } }
    }

    private fun Event.actors(): List<UUID> = when (this) {
        is OnePlayerEvent -> listOf(participant)
        is TwoPlayerEvent -> listOf(fromParticipant, toParticipant)
        else -> emptyList()
    }

    /** The proposed journal must still bind every surviving slot reference to its own point. */
    fun validateRemainingSlots(match: Match, proposed: List<StoredEvent>) {
        if (slotPoints.isEmpty()) return
        val pointByOpeningEvent = PointTimeline.from(match.id, MatchStatus.IN_PROGRESS, proposed)
            .associateBy { it.openingEventId }
        var pointId = pointByOpeningEvent.getValue(null).pointId
        val seenEvents = mutableSetOf<UUID>()
        for ((id, _, event) in proposed) {
            seenEvents.add(id)
            if (event.actors().any { introduced[it]?.slot?.introducedByEventId?.let { intro -> intro !in seenEvents } == true }) {
                substitutionConflict("A temporary reserve cannot be referenced without its introduction")
            }
            if (event.actors().any { slotPoints[it]?.let { original -> original != pointId } == true }) {
                conflict("The proposed journal would move a temporary actor outside its confirmed point")
            }
            pointByOpeningEvent[id]?.let { pointId = it.pointId }
        }
    }

    private fun conflict(detail: String): Nothing = throw PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CONFLICT, detail,
    )
}

internal fun substitutionConflict(detail: String): Nothing = throw PointLineupRejectedException(
    PointLineupProblemCode.POINT_SUBSTITUTION_CONFLICT, detail,
)
