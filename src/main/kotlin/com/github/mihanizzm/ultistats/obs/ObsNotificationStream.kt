package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchFinishedRealtimeResponse
import com.github.mihanizzm.ultistats.realtime.EventLogChangedSignal
import com.github.mihanizzm.ultistats.realtime.MatchChangedSignal
import com.github.mihanizzm.ultistats.realtime.MatchFinishedSignal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

/** OBS-only invalidations shared by a browser worker; existing SSE contracts stay unchanged. */
@Service
class ObsNotificationStream {
    private val clients = ConcurrentHashMap.newKeySet<SseEmitter>()

    fun subscribe(): SseEmitter {
        val emitter = SseEmitter(0L)
        clients.add(emitter)
        emitter.onCompletion { clients.remove(emitter) }
        emitter.onTimeout { clients.remove(emitter) }
        emitter.onError { clients.remove(emitter) }
        deliver(emitter, SseEmitter.event().name("connected").data(mapOf("scope" to "service")).reconnectTime(2000))
        return emitter
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun configChanged(signal: ObsConfigChanged) = broadcast("config-changed", mapOf("scope" to "service"))

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun eventChanged(signal: EventLogChangedSignal) = broadcast(
        "event-log-changed", EventLogChangedRealtimeResponse(signal.matchId, signal.operation, signal.eventId),
    )

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun matchFinished(signal: MatchFinishedSignal) = broadcast("match-finished", MatchFinishedRealtimeResponse(signal.matchId))

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun matchChanged(signal: MatchChangedSignal) = broadcast("match-changed", mapOf("matchId" to signal.matchId))

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun profileChanged(signal: ScoreboardProfileChanged) = broadcast("scoreboard-profile-changed", mapOf("matchId" to signal.matchId))

    @Scheduled(fixedDelay = 15000)
    fun heartbeat() { clients.forEach { deliver(it, SseEmitter.event().comment("heartbeat")) } }

    private fun broadcast(name: String, data: Any) {
        clients.forEach { deliver(it, SseEmitter.event().name(name).data(data)) }
    }

    private fun deliver(emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
        synchronized(emitter) {
            try { emitter.send(event) } catch (cause: Exception) {
                clients.remove(emitter)
                runCatching { emitter.completeWithError(cause) }
            }
        }
    }
}

@RestController
@SecurityRequirement(name = "staffSession")
class ObsNotificationController {
    @GetMapping("/api/v1/obs/stream")
    @Operation(summary = "Retired shared OBS stream", description = "OBS_ADMIN only. Always 410; cached legacy workers cannot open an event channel. General sports streams are unchanged.", deprecated = true)
    @ApiResponse(responseCode = "410", description = "Retired; no event channel")
    fun subscribe() = retiredObsStream()
}
