package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.events.AnnouncementStartEvent
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import java.time.Instant
import java.util.UUID

/** An ordered statistics observation, with scoring authority independent of action attribution. */
data class StatisticsEvent(
    val type: EventType,
    val occurredAt: Instant?,
    private val fromActorId: UUID? = null,
    private val toActorId: UUID? = null,
    val teamId: UUID? = null,
    val source: Source = Source.JOURNAL,
) {
    enum class Source { JOURNAL, RESTORED, RESULT_MARKER }

    val fromActor: UUID get() = requireNotNull(fromActorId)
    val toActor: UUID get() = requireNotNull(toActorId)
    val scoresPoint: Boolean get() = type.closesPoint && source != Source.RESTORED

    companion object {
        fun original(event: Event): StatisticsEvent = when (event) {
            is OnePlayerEvent -> StatisticsEvent(event.type, event.occurredAt, fromActorId = event.participant)
            is TwoPlayerEvent -> StatisticsEvent(event.type, event.occurredAt, event.fromParticipant, event.toParticipant)
            is TeamEvent -> StatisticsEvent(event.type, event.occurredAt, teamId = event.team)
            is AnnouncementStartEvent -> StatisticsEvent(event.type, event.occurredAt, teamId = event.team)
            else -> StatisticsEvent(event.type, event.occurredAt)
        }
    }
}
