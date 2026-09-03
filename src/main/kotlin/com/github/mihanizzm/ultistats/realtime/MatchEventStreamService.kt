package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.ConnectedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchFinishedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.service.MatchService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface MatchEventStreamResult {
    data class Opened(val emitter: SseEmitter) : MatchEventStreamResult
    data object NotFound : MatchEventStreamResult
}

@Service
class MatchEventStreamService(
    private val matchService: MatchService,
    private val connectionFactory: MatchEventConnectionFactory,
    private val properties: MatchRealtimeProperties,
) {
    private val subscribersByMatch = ConcurrentHashMap<UUID, MutableSet<Subscriber>>()

    fun subscribe(matchId: UUID): MatchEventStreamResult {
        if (matchService.get(matchId) == null) {
            return MatchEventStreamResult.NotFound
        }

        val connection = connectionFactory.create(0)
        val subscriber = Subscriber(connection) { dead -> removeSubscriber(matchId, dead) }
        subscribersByMatch.computeIfAbsent(matchId) { ConcurrentHashMap.newKeySet() }.add(subscriber)
        connection.onCompletion(subscriber::markDead)
        connection.onTimeout(subscriber::markDead)
        connection.onError { subscriber.markDead() }

        val initialized = subscriber.initialize(
            NamedMessage(
                name = "connected",
                data = ConnectedRealtimeResponse(matchId),
                reconnectTimeMs = properties.reconnectTimeMs,
            ),
        )
        if (initialized) {
            val currentMatch = matchService.get(matchId)
            when {
                currentMatch == null -> subscriber.complete()
                currentMatch.status == MatchStatus.FINISHED -> subscriber.finish(
                    NamedMessage("match-finished", MatchFinishedRealtimeResponse(matchId)),
                )
            }
        }
        return MatchEventStreamResult.Opened(connection.emitter)
    }

    fun eventLogChanged(matchId: UUID, operation: MatchRealtimeOperation, eventId: UUID) {
        broadcast(
            matchId,
            NamedMessage(
                "event-log-changed",
                EventLogChangedRealtimeResponse(matchId, operation, eventId),
            ),
        )
    }

    fun finishMatch(matchId: UUID) {
        subscribersByMatch[matchId]?.forEach { subscriber ->
            subscriber.finish(NamedMessage("match-finished", MatchFinishedRealtimeResponse(matchId)))
        }
    }

    @Scheduled(fixedDelayString = "#{@matchRealtimeProperties.heartbeatIntervalMs}")
    fun heartbeat() {
        subscribersByMatch.values.flatten().forEach { subscriber ->
            subscriber.heartbeat()
        }
    }

    private fun broadcast(matchId: UUID, message: NamedMessage) {
        subscribersByMatch[matchId]?.forEach { subscriber -> subscriber.send(message) }
    }

    private fun removeSubscriber(matchId: UUID, subscriber: Subscriber) {
        subscribersByMatch.computeIfPresent(matchId) { _, subscribers ->
            subscribers.remove(subscriber)
            subscribers.takeUnless { it.isEmpty() }
        }
    }

    private class Subscriber(
        val connection: MatchEventConnection,
        private val onDead: (Subscriber) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private val pending = ArrayDeque<NamedMessage>()
        private var initialized = false
        private var completed = false
        private var finishAfterInitialization = false

        fun initialize(connected: NamedMessage): Boolean = lock.withLock {
            if (completed) return false
            if (!sendNow(connected)) return false
            initialized = true
            while (pending.isNotEmpty() && !completed) sendNow(pending.removeFirst())
            if (finishAfterInitialization && !completed) completeNow()
            !completed
        }

        fun send(message: NamedMessage): Unit = lock.withLock {
            if (completed) return
            if (!initialized) pending.addLast(message) else sendNow(message)
        }

        fun heartbeat(): Unit = lock.withLock {
            if (!completed && initialized) runDelivery { connection.comment("heartbeat") }
        }

        fun finish(message: NamedMessage): Unit = lock.withLock {
            if (completed) return
            if (!initialized) {
                pending.addLast(message)
                finishAfterInitialization = true
            } else if (sendNow(message)) {
                completeNow()
            }
        }

        fun complete(): Unit = lock.withLock {
            completeNow()
        }

        fun markDead(): Unit = lock.withLock {
            if (completed) return
            completed = true
            pending.clear()
            onDead(this)
        }

        private fun sendNow(message: NamedMessage): Boolean = runDelivery {
            connection.send(message.name, message.data, message.reconnectTimeMs)
        }

        private fun runDelivery(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (cause: Exception) {
            completed = true
            pending.clear()
            onDead(this)
            runCatching { connection.completeWithError(cause) }
            false
        }

        private fun completeNow() {
            if (completed) return
            completed = true
            pending.clear()
            onDead(this)
            runCatching { connection.complete() }
        }
    }

    private data class NamedMessage(
        val name: String,
        val data: Any,
        val reconnectTimeMs: Long? = null,
    )
}
