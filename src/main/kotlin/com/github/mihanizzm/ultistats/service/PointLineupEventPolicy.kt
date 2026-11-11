package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.PointTimeline
import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchParticipantRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PointLineupEventPolicy(
    private val lineups: PointLineupRepository,
    private val participants: SpringDataMatchParticipantRepository,
    @Value("\${ultistats.point-lineups.write-enabled:false}") private val writesEnabled: Boolean,
) {
    fun requireWriteClient(matchId: UUID, capable: Boolean) {
        if (!capable && lineups.exists(matchId)) requireClient()
    }

    fun requireReadClient(events: Iterable<EventEntity>, capable: Boolean) {
        if (!capable && events.any { it.fromSlotId != null || it.toSlotId != null }) requireClient()
    }

    /** Write callers obtain this view while holding the match row lock. */
    fun journal(matchId: UUID): ConfirmedJournal? {
        if (!lineups.exists(matchId)) return null
        val roster = participants.findAllByMatchId(matchId)
        return ConfirmedJournal(
            lineups.list(matchId, roster.map { it.teamId }.distinct()),
            roster.filter { it.kind == MatchParticipantKind.UNKNOWN }.map { it.participantId }.toSet(),
            writesEnabled,
        )
    }

    private fun requireClient(): Nothing = throw PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CLIENT_REQUIRED, "This journal requires a point-lineup-aware client",
    )
}

class ConfirmedJournal(
    private val lineups: Map<UUID, PointLineup>,
    private val unknownIds: Set<UUID>,
    private val writesEnabled: Boolean,
) {
    private val entriesByPoint = lineups.mapValues { (_, lineup) -> lineup.teams.flatMap { it.entries } }
    private val slotPoints = buildMap {
        for ((pointId, actors) in entriesByPoint) {
            actors.filterIsInstance<PointLineupTemporary>().forEach { put(it.slotId, pointId) }
        }
    }
    val slotIds: Set<UUID> get() = slotPoints.keys

    fun validateWrite(match: Match, prefix: List<StoredEvent>, event: Event, append: Boolean) {
        val actors = when (event) {
            is OnePlayerEvent -> listOf(event.participant)
            is TwoPlayerEvent -> listOf(event.fromParticipant, event.toParticipant)
            else -> return
        }
        val hasSlot = actors.any(slotIds::contains)
        if (hasSlot && !writesEnabled) {
            throw PointLineupRejectedException(
                PointLineupProblemCode.POINT_LINEUP_WRITE_DISABLED, "Point lineup writes are disabled",
            )
        }
        // Use the open point of this prefix even when editing a finished match.
        val point = PointTimeline.from(match.id, MatchStatus.IN_PROGRESS, prefix).last()
        val members = entriesByPoint[point.pointId]
        if (members == null) {
            if (append && event.type == EventType.PULL) conflict("Confirm the next point lineup before recording PULL")
            if (hasSlot) conflict("Temporary actors must belong to the current confirmed point")
            return
        }
        val allowed = members.map {
            when (it) {
                is PointLineupPlayer -> it.participantId
                is PointLineupTemporary -> it.slotId
            }
        }
        if (actors.any { it !in allowed && it !in unknownIds }) {
            conflict("Event actors must belong to the current confirmed point or be match-wide UNKNOWN participants")
        }
    }

    /** The proposed journal must still bind every surviving slot reference to its own point. */
    fun validateRemainingSlots(match: Match, proposed: List<StoredEvent>) {
        if (slotPoints.isEmpty()) return
        val pointByOpeningEvent = PointTimeline.from(match.id, MatchStatus.IN_PROGRESS, proposed)
            .associateBy { it.openingEventId }
        var pointId = pointByOpeningEvent.getValue(null).pointId
        for ((id, _, event) in proposed) {
            val slots = when (event) {
                is OnePlayerEvent -> listOf(event.participant)
                is TwoPlayerEvent -> listOf(event.fromParticipant, event.toParticipant)
                else -> emptyList()
            }
            if (slots.any { slotPoints[it]?.let { original -> original != pointId } == true }) {
                conflict("The proposed journal would move a temporary actor outside its confirmed point")
            }
            pointByOpeningEvent[id]?.let { pointId = it.pointId }
        }
    }

    private fun conflict(detail: String): Nothing = throw PointLineupRejectedException(
        PointLineupProblemCode.POINT_LINEUP_CONFLICT, detail,
    )
}
