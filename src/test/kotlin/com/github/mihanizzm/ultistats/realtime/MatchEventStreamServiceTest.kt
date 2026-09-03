package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.ConnectedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.service.MatchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertIs

@Suppress("NonAsciiCharacters")
class MatchEventStreamServiceTest {
    private val matchService = mock(MatchService::class.java)
    private val factory = FakeConnectionFactory()
    private val service = MatchEventStreamService(
        matchService = matchService,
        connectionFactory = factory,
        properties = MatchRealtimeProperties(),
    )

    @Test
    fun `подписка начинает поток connected и изменения изолированы по матчу`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        `when`(matchService.get(OTHER_MATCH_ID)).thenReturn(activeMatch(OTHER_MATCH_ID))

        val first = factory.enqueue()
        val other = factory.enqueue()
        assertIs<MatchEventStreamResult.Opened>(service.subscribe(MATCH_ID))
        assertIs<MatchEventStreamResult.Opened>(service.subscribe(OTHER_MATCH_ID))

        assertThat(first.events).containsExactly(
            SentEvent("connected", ConnectedRealtimeResponse(MATCH_ID), 2_000),
        )

        service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.CREATED, EVENT_ID)

        assertThat(first.events.last()).isEqualTo(
            SentEvent(
                "event-log-changed",
                EventLogChangedRealtimeResponse(MATCH_ID, MatchRealtimeOperation.CREATED, EVENT_ID),
                null,
            ),
        )
        assertThat(other.events).hasSize(1)
    }

    @Test
    fun `подписка на отсутствующий матч не создаёт соединение`() {
        assertIs<MatchEventStreamResult.NotFound>(service.subscribe(MISSING_MATCH_ID))
    }

    private fun activeMatch(id: UUID) = Match(
        id = id,
        teamIds = listOf(TEAM_ID, OTHER_TEAM_ID),
        startedAt = Instant.parse("2026-09-02T10:00:00Z"),
    )

    private data class SentEvent(val name: String, val data: Any, val reconnectTimeMs: Long?)

    private class FakeConnection(
        private val failOnComment: Boolean = false,
    ) : MatchEventConnection {
        override val emitter = SseEmitter(0)
        val events = mutableListOf<SentEvent>()
        val comments = mutableListOf<String>()
        var completed = false
        private var completion: () -> Unit = {}
        private var timeout: () -> Unit = {}
        private var error: (Throwable) -> Unit = {}

        override fun send(name: String, data: Any, reconnectTimeMs: Long?) {
            events += SentEvent(name, data, reconnectTimeMs)
        }

        override fun comment(value: String) {
            if (failOnComment) throw IOException("disconnected")
            comments += value
        }

        override fun complete() {
            completed = true
            completion()
        }

        override fun completeWithError(cause: Throwable) {
            completed = true
            error(cause)
        }

        override fun onCompletion(callback: () -> Unit) {
            completion = callback
        }

        override fun onTimeout(callback: () -> Unit) {
            timeout = callback
        }

        override fun onError(callback: (Throwable) -> Unit) {
            error = callback
        }

        fun disconnect() = completion()

        fun timeOut() = timeout()

        fun fail(cause: Throwable) = error(cause)
    }

    private class FakeConnectionFactory : MatchEventConnectionFactory {
        private val queued = ArrayDeque<FakeConnection>()

        fun enqueue(failOnComment: Boolean = false): FakeConnection =
            FakeConnection(failOnComment).also(queued::addLast)

        override fun create(timeoutMs: Long): MatchEventConnection = queued.removeFirst()
    }

    private companion object {
        val MATCH_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val OTHER_MATCH_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val MISSING_MATCH_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
        val EVENT_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val TEAM_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val OTHER_TEAM_ID: UUID = UUID.fromString("30000000-0000-0000-0000-000000000002")
    }
}
