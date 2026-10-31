package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import java.util.UUID

sealed interface MatchRealtimeSignal {
    val matchId: UUID
}

data class EventLogChangedSignal(
    override val matchId: UUID,
    val operation: MatchRealtimeOperation,
    val eventId: UUID,
) : MatchRealtimeSignal

data class MatchFinishedSignal(
    override val matchId: UUID,
) : MatchRealtimeSignal

/** Additive OBS invalidation; existing match SSE event contracts do not consume it. */
data class MatchChangedSignal(override val matchId: UUID) : MatchRealtimeSignal
