package com.github.mihanizzm.ultistats.dto.response.realtime

import java.util.UUID

enum class MatchRealtimeOperation {
    CREATED,
    UPDATED,
    DELETED,
}

data class ConnectedRealtimeResponse(val matchId: UUID)

data class EventLogChangedRealtimeResponse(
    val matchId: UUID,
    val operation: MatchRealtimeOperation,
    val eventId: UUID,
)

data class MatchFinishedRealtimeResponse(val matchId: UUID)
