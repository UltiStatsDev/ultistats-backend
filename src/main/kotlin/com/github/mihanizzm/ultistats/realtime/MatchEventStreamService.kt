package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.ConnectedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchFinishedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.service.MatchService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        subscribersByMatch.computeIfAbsent(matchId) { ConcurrentHashMap.newKeySet() }
            .add(Subscriber(connection))
        connection.send("connected", ConnectedRealtimeResponse(matchId), properties.reconnectTimeMs)
        return MatchEventStreamResult.Opened(connection.emitter)
    }

    fun eventLogChanged(matchId: UUID, operation: MatchRealtimeOperation, eventId: UUID) {
        broadcast(matchId) { connection ->
            connection.send(
                "event-log-changed",
                EventLogChangedRealtimeResponse(matchId, operation, eventId),
            )
        }
    }

    fun finishMatch(matchId: UUID) {
        broadcast(matchId) { connection ->
            connection.send("match-finished", MatchFinishedRealtimeResponse(matchId))
        }
    }

    @Scheduled(fixedDelayString = "#{@matchRealtimeProperties.heartbeatIntervalMs}")
    fun heartbeat() {
        subscribersByMatch.values.flatten().forEach { subscriber ->
            subscriber.connection.comment("heartbeat")
        }
    }

    private fun broadcast(matchId: UUID, send: (MatchEventConnection) -> Unit) {
        subscribersByMatch[matchId]?.forEach { subscriber -> send(subscriber.connection) }
    }

    private data class Subscriber(val connection: MatchEventConnection)
}
