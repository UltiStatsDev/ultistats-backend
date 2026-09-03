package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.dto.response.realtime.ConnectedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.EventLogChangedRealtimeResponse
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchFinishedRealtimeResponse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    @Test
    fun `два подписчика получают изменение а отключенный больше не вызывается`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val first = factory.enqueue()
        val second = factory.enqueue()
        service.subscribe(MATCH_ID)
        service.subscribe(MATCH_ID)
        first.disconnect()

        service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.UPDATED, EVENT_ID)

        assertThat(first.events).hasSize(1)
        assertThat(second.events.map { it.name }).containsExactly("connected", "event-log-changed")
    }

    @Test
    fun `регистрация не теряется при одновременном удалении последнего подписчика`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val first = factory.enqueue()
        service.subscribe(MATCH_ID)
        val registry = subscriberRegistry()
        val firstSubscriber = registry.getValue(MATCH_ID).single()
        val addStarted = CountDownLatch(1)
        val releaseAdd = CountDownLatch(1)
        val removeStarted = CountDownLatch(1)
        registry[MATCH_ID] = BlockingMutableSet(
            initial = firstSubscriber,
            addStarted = addStarted,
            releaseAdd = releaseAdd,
            removeStarted = removeStarted,
        )
        val second = factory.enqueue()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val subscription = executor.submit<MatchEventStreamResult> { service.subscribe(MATCH_ID) }
            assertThat(addStarted.await(2, TimeUnit.SECONDS)).isTrue()
            val disconnection = executor.submit { first.disconnect() }
            removeStarted.await(1, TimeUnit.SECONDS)
            releaseAdd.countDown()

            assertIs<MatchEventStreamResult.Opened>(subscription.get(2, TimeUnit.SECONDS))
            disconnection.get(2, TimeUnit.SECONDS)
            service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.UPDATED, EVENT_ID)

            assertThat(second.events.map { it.name }).containsExactly("connected", "event-log-changed")
        } finally {
            releaseAdd.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `heartbeat удаляет только сломанное соединение`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val healthy = factory.enqueue()
        val broken = factory.enqueue(failOnComment = true)
        service.subscribe(MATCH_ID)
        service.subscribe(MATCH_ID)

        service.heartbeat()
        service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.CREATED, EVENT_ID)

        assertThat(healthy.comments).containsExactly("heartbeat")
        assertThat(healthy.events.last().name).isEqualTo("event-log-changed")
        assertThat(broken.events).hasSize(1)
    }

    @Test
    fun `finished отправляется один раз завершает подписки и очищает матч`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val connection = factory.enqueue()
        service.subscribe(MATCH_ID)

        service.finishMatch(MATCH_ID)
        service.finishMatch(MATCH_ID)

        assertThat(connection.events.map { it.name }).containsExactly("connected", "match-finished")
        assertThat(connection.completed).isTrue()
    }

    @Test
    fun `finished до инициализации отбрасывает дубликат и последующие изменения`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val registrationStarted = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val connection = factory.enqueue(
            registrationStarted = registrationStarted,
            releaseRegistration = releaseRegistration,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val subscription = executor.submit<MatchEventStreamResult> { service.subscribe(MATCH_ID) }
            assertThat(registrationStarted.await(2, TimeUnit.SECONDS)).isTrue()

            service.finishMatch(MATCH_ID)
            service.finishMatch(MATCH_ID)
            service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.UPDATED, EVENT_ID)
            releaseRegistration.countDown()

            assertIs<MatchEventStreamResult.Opened>(subscription.get(2, TimeUnit.SECONDS))
            assertThat(connection.events.map { it.name }).containsExactly("connected", "match-finished")
            assertThat(connection.completed).isTrue()
        } finally {
            releaseRegistration.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `изменение во время инициализации отправляется после connected`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID))
        val connectedStarted = CountDownLatch(1)
        val releaseConnected = CountDownLatch(1)
        val eventSendAttempted = CountDownLatch(1)
        val connection = factory.enqueue(
            connectedStarted = connectedStarted,
            releaseConnected = releaseConnected,
            eventSendAttempted = eventSendAttempted,
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val subscription = executor.submit<MatchEventStreamResult> { service.subscribe(MATCH_ID) }
            assertThat(connectedStarted.await(2, TimeUnit.SECONDS)).isTrue()

            val broadcast = executor.submit {
                service.eventLogChanged(MATCH_ID, MatchRealtimeOperation.UPDATED, EVENT_ID)
            }
            eventSendAttempted.await(1, TimeUnit.SECONDS)
            releaseConnected.countDown()

            assertIs<MatchEventStreamResult.Opened>(subscription.get(2, TimeUnit.SECONDS))
            broadcast.get(2, TimeUnit.SECONDS)
            assertThat(connection.events.map { it.name }).containsExactly("connected", "event-log-changed")
        } finally {
            releaseConnected.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `завершение матча при повторной проверке отправляет finished после connected`() {
        `when`(matchService.get(MATCH_ID)).thenReturn(activeMatch(MATCH_ID), finishedMatch(MATCH_ID))
        val connection = factory.enqueue()

        assertIs<MatchEventStreamResult.Opened>(service.subscribe(MATCH_ID))

        assertThat(connection.events).containsExactly(
            SentEvent("connected", ConnectedRealtimeResponse(MATCH_ID), 2_000),
            SentEvent("match-finished", MatchFinishedRealtimeResponse(MATCH_ID), null),
        )
        assertThat(connection.completed).isTrue()
    }

    private fun activeMatch(id: UUID) = Match(
        id = id,
        teamIds = listOf(TEAM_ID, OTHER_TEAM_ID),
        startedAt = Instant.parse("2026-09-02T10:00:00Z"),
    )

    private fun finishedMatch(id: UUID) = activeMatch(id).copy(
        endedAt = Instant.parse("2026-09-02T12:00:00Z"),
    )

    @Suppress("UNCHECKED_CAST")
    private fun subscriberRegistry(): ConcurrentHashMap<UUID, MutableSet<Any>> {
        val field = MatchEventStreamService::class.java.getDeclaredField("subscribersByMatch")
        field.isAccessible = true
        return field.get(service) as ConcurrentHashMap<UUID, MutableSet<Any>>
    }

    private data class SentEvent(val name: String, val data: Any, val reconnectTimeMs: Long?)

    private class BlockingMutableSet(
        initial: Any,
        private val addStarted: CountDownLatch,
        private val releaseAdd: CountDownLatch,
        private val removeStarted: CountDownLatch,
    ) : AbstractMutableSet<Any>() {
        private val delegate = mutableSetOf(initial)

        override val size: Int
            get() = delegate.size

        override fun add(element: Any): Boolean {
            addStarted.countDown()
            check(releaseAdd.await(2, TimeUnit.SECONDS)) { "subscriber add was not released" }
            return delegate.add(element)
        }

        override fun iterator(): MutableIterator<Any> = delegate.iterator()

        override fun remove(element: Any): Boolean {
            removeStarted.countDown()
            return delegate.remove(element)
        }
    }

    private class FakeConnection(
        private val failOnComment: Boolean = false,
        private val connectedStarted: CountDownLatch? = null,
        private val releaseConnected: CountDownLatch? = null,
        private val eventSendAttempted: CountDownLatch? = null,
        private val registrationStarted: CountDownLatch? = null,
        private val releaseRegistration: CountDownLatch? = null,
    ) : MatchEventConnection {
        override val emitter = SseEmitter(0)
        val events = mutableListOf<SentEvent>()
        val comments = mutableListOf<String>()
        var completed = false
        private var completion: () -> Unit = {}
        private var timeout: () -> Unit = {}
        private var error: (Throwable) -> Unit = {}

        override fun send(name: String, data: Any, reconnectTimeMs: Long?) {
            if (name == "connected") {
                connectedStarted?.countDown()
                check(releaseConnected?.await(2, TimeUnit.SECONDS) != false) {
                    "connected send was not released"
                }
            }
            if (name == "event-log-changed") {
                eventSendAttempted?.countDown()
            }
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
            registrationStarted?.countDown()
            check(releaseRegistration?.await(2, TimeUnit.SECONDS) != false) {
                "registration was not released"
            }
        }

        fun disconnect() = completion()

        fun timeOut() = timeout()

        fun fail(cause: Throwable) = error(cause)
    }

    private class FakeConnectionFactory : MatchEventConnectionFactory {
        private val queued = ArrayDeque<FakeConnection>()

        fun enqueue(
            failOnComment: Boolean = false,
            connectedStarted: CountDownLatch? = null,
            releaseConnected: CountDownLatch? = null,
            eventSendAttempted: CountDownLatch? = null,
            registrationStarted: CountDownLatch? = null,
            releaseRegistration: CountDownLatch? = null,
        ): FakeConnection = FakeConnection(
            failOnComment = failOnComment,
            connectedStarted = connectedStarted,
            releaseConnected = releaseConnected,
            eventSendAttempted = eventSendAttempted,
            registrationStarted = registrationStarted,
            releaseRegistration = releaseRegistration,
        ).also(queued::addLast)

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
