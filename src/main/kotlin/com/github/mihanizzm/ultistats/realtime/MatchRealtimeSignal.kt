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
