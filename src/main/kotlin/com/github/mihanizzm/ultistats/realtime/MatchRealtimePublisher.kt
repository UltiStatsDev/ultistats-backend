package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MatchRealtimePublisher(
    private val delegate: ApplicationEventPublisher,
) {
    fun eventLogChanged(matchId: UUID, operation: MatchRealtimeOperation, eventId: UUID) {
        delegate.publishEvent(EventLogChangedSignal(matchId, operation, eventId))
    }

    fun matchFinished(matchId: UUID) {
        delegate.publishEvent(MatchFinishedSignal(matchId))
    }
}
