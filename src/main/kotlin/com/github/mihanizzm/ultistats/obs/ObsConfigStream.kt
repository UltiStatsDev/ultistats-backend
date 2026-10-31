package com.github.mihanizzm.ultistats.obs

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Service
class ObsConfigStream {
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
    fun changed(event: ObsConfigChanged) {
        clients.forEach { deliver(it, SseEmitter.event().name("config-changed").data(mapOf("scope" to "service"))) }
    }

    @Scheduled(fixedDelay = 15000)
    fun heartbeat() { clients.forEach { deliver(it, SseEmitter.event().comment("heartbeat")) } }

    private fun deliver(emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
        synchronized(emitter) {
            try { emitter.send(event) } catch (cause: Exception) {
                clients.remove(emitter)
                runCatching { emitter.completeWithError(cause) }
            }
        }
    }
}
