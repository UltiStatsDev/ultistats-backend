package com.github.mihanizzm.ultistats.realtime

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class MatchRealtimeSignalListener(
    private val streamService: MatchEventStreamService,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventLogChanged(signal: EventLogChangedSignal) {
        runCatching {
            streamService.eventLogChanged(signal.matchId, signal.operation, signal.eventId)
        }.onFailure { cause ->
            logger.warn("Failed to deliver event-log change for matchId=${signal.matchId}", cause)
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMatchFinished(signal: MatchFinishedSignal) {
        runCatching {
            streamService.finishMatch(signal.matchId)
        }.onFailure { cause ->
            logger.warn("Failed to deliver match-finished signal for matchId=${signal.matchId}", cause)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MatchRealtimeSignalListener::class.java)
    }
}
