package com.github.mihanizzm.ultistats.model.events

import com.github.mihanizzm.ultistats.model.MatchStatus
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

enum class PointStatus { NEXT, ACTIVE, COMPLETED }

data class PointBoundary(
    val pointId: UUID,
    val number: Int,
    val openingEventId: UUID?,
    val pullEventId: UUID? = null,
    val closingEventId: UUID? = null,
) {
    val status: PointStatus
        get() = when {
            closingEventId != null -> PointStatus.COMPLETED
            pullEventId != null -> PointStatus.ACTIVE
            else -> PointStatus.NEXT
        }
}

/** Derives canonical point boundaries from an active journal (or prefix) in sequence order. */
object PointTimeline {
    fun from(matchId: UUID, status: MatchStatus, activeEvents: Iterable<StoredEvent>): List<PointBoundary> {
        if (status == MatchStatus.PLANNED) return emptyList()

        var current = open(matchId, 1, null)
        return buildList {
            for (event in activeEvents) {
                when (event.event.type) {
                    EventType.PULL -> current = current.copy(pullEventId = event.id)
                    EventType.GOAL, EventType.CALLAHAN -> {
                        add(current.copy(closingEventId = event.id))
                        current = open(matchId, current.number + 1, event.id)
                    }
                    else -> Unit
                }
            }
            if (status == MatchStatus.IN_PROGRESS) add(current)
        }
    }

    /** Assign scoring events to the point they close. An opening score belongs to the previous point. */
    fun eventsByPoint(points: List<PointBoundary>, events: List<StoredEvent>): Map<UUID, List<StoredEvent>> {
        var pointId = points.firstOrNull()?.pointId ?: return emptyMap()
        val successorByScore = points.drop(1).associate { it.openingEventId to it.pointId }
        val groups = mutableMapOf<UUID, MutableList<StoredEvent>>()
        for (event in events) {
            groups.getOrPut(pointId) { mutableListOf() }.add(event)
            pointId = successorByScore[event.id] ?: pointId
        }
        return groups
    }

    private fun open(matchId: UUID, number: Int, openingEventId: UUID?): PointBoundary {
        val key = "ultistats:point:v1:$matchId:${openingEventId ?: "first"}"
        return PointBoundary(UUID.nameUUIDFromBytes(key.toByteArray(UTF_8)), number, openingEventId)
    }
}
