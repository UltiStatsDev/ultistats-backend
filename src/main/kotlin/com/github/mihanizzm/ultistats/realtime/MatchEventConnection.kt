package com.github.mihanizzm.ultistats.realtime

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

interface MatchEventConnection {
    val emitter: SseEmitter
    fun send(name: String, data: Any, reconnectTimeMs: Long? = null)
    fun comment(value: String)
    fun complete()
    fun completeWithError(cause: Throwable)
    fun onCompletion(callback: () -> Unit)
    fun onTimeout(callback: () -> Unit)
    fun onError(callback: (Throwable) -> Unit)
}

fun interface MatchEventConnectionFactory {
    fun create(timeoutMs: Long): MatchEventConnection
}

@Component
class SseEmitterMatchEventConnectionFactory : MatchEventConnectionFactory {
    override fun create(timeoutMs: Long): MatchEventConnection =
        SseEmitterMatchEventConnection(SseEmitter(timeoutMs))

    private class SseEmitterMatchEventConnection(
        override val emitter: SseEmitter,
    ) : MatchEventConnection {
        override fun send(name: String, data: Any, reconnectTimeMs: Long?) {
            var event = SseEmitter.event().name(name).data(data)
            if (reconnectTimeMs != null) {
                event = event.reconnectTime(reconnectTimeMs)
            }
            emitter.send(event)
        }

        override fun comment(value: String) {
            emitter.send(SseEmitter.event().comment(value))
        }

        override fun complete() {
            emitter.complete()
        }

        override fun completeWithError(cause: Throwable) {
            emitter.completeWithError(cause)
        }

        override fun onCompletion(callback: () -> Unit) {
            emitter.onCompletion(callback)
        }

        override fun onTimeout(callback: () -> Unit) {
            emitter.onTimeout(callback)
        }

        override fun onError(callback: (Throwable) -> Unit) {
            emitter.onError(callback)
        }
    }
}
