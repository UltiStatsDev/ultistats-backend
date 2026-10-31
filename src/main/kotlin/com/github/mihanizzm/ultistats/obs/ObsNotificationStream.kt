package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchFinishedRealtimeResponse
import com.github.mihanizzm.ultistats.realtime.EventLogChangedSignal
import com.github.mihanizzm.ultistats.realtime.MatchFinishedSignal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
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
class ObsNotificationController(private val stream: ObsNotificationStream) {
    @GetMapping("/api/v1/obs/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Общий поток уведомлений OBS для SharedWorker", description = "Один поток на browser context: connected/config-changed/event-log-changed/match-finished. Только AFTER_COMMIT invalidations; canonical данные читаются через REST. Match-события содержат matchId для фильтрации. После match-finished поток остаётся открыт для общей конфигурации и других матчей. Process-local без durable replay; connected требует повторной сверки всех подписок. Доверенная сеть без авторизации.")
    @ApiResponse(responseCode = "200", description = "OBS notification SSE", content = [Content(
        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = Schema(type = "string"),
    )])
    fun subscribe(): ResponseEntity<SseEmitter> = ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(stream.subscribe())
}
