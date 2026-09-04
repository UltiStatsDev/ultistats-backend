package com.github.mihanizzm.ultistats.realtime

import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.service.result.EventCommandResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.after
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Suppress("NonAsciiCharacters")
class MatchRealtimeTransactionIntegrationTest : MatchAbstractTest() {

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @MockitoSpyBean
    lateinit var streamService: MatchEventStreamService

    @BeforeEach
    fun clearStreamInvocations() {
        clearInvocations(streamService)
    }

    @Test
    fun `После commit создания публикуется точный идентификатор события`() {
        val created = assertIs<EventCommandResult.Success>(
            eventService.create(validPull(), MATCH.id),
        ).event

        verify(streamService, timeout(1_000).times(1)).eventLogChanged(
            MATCH.id,
            MatchRealtimeOperation.CREATED,
            created.id,
        )
    }

    @Test
    fun `После commit изменения публикуется идентификатор сохраненного события`() {
        val stored = assertIs<EventCommandResult.Success>(
            eventService.create(validPull(), MATCH.id),
        ).event
        clearInvocations(streamService)
        val replacement = OnePlayerEvent(PLAYERS_2[1].id, stored.event.occurredAt, EventType.PULL)

        val updated = assertIs<EventCommandResult.Success>(
            eventService.update(stored.id, MATCH.id) { replacement },
        ).event

        assertEquals(stored.id, updated.id)
        verify(streamService, timeout(1_000).times(1)).eventLogChanged(
            MATCH.id,
            MatchRealtimeOperation.UPDATED,
            stored.id,
        )
    }

    @Test
    fun `После commit удаления публикуется идентификатор удаленного события`() {
        val stored = assertIs<EventCommandResult.Success>(
            eventService.create(validPull(), MATCH.id),
        ).event
        clearInvocations(streamService)

        assertIs<EventCommandResult.Deleted>(eventService.remove(stored.id, MATCH.id))

        verify(streamService, timeout(1_000).times(1)).eventLogChanged(
            MATCH.id,
            MatchRealtimeOperation.DELETED,
            stored.id,
        )
    }

    @Test
    fun `Rollback создания не публикует изменение event log`() {
        transactionTemplate.executeWithoutResult { status ->
            assertIs<EventCommandResult.Success>(eventService.create(validPull(), MATCH.id))
            status.setRollbackOnly()
        }

        verify(streamService, after(200).never()).eventLogChanged(
            matching(MATCH.id),
            anyOperation(),
            anyEventId(),
        )
    }

    @Test
    fun `Отклоненная последовательность не публикует изменение event log`() {
        val pass = TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, EVENT_AT, EventType.PASS)

        assertIs<EventCommandResult.Conflict>(eventService.create(pass, MATCH.id))

        verify(streamService, after(200).never()).eventLogChanged(
            matching(MATCH.id),
            anyOperation(),
            anyEventId(),
        )
    }

    @Test
    fun `Ошибка stream listener не изменяет успешный commit`() {
        doThrow(IllegalStateException("stream delivery failed"))
            .`when`(streamService)
            .eventLogChanged(matching(MATCH.id), matching(MatchRealtimeOperation.CREATED), anyEventId())

        val created = assertIs<EventCommandResult.Success>(
            eventService.create(validPull(), MATCH.id),
        ).event

        verify(streamService, timeout(1_000).times(1)).eventLogChanged(
            MATCH.id,
            MatchRealtimeOperation.CREATED,
            created.id,
        )
        assertEquals(created, eventService.get(created.id, MATCH.id))
    }

    private fun validPull() = OnePlayerEvent(PLAYERS_2[0].id, EVENT_AT, EventType.PULL)

    private fun anyOperation(): MatchRealtimeOperation =
        any(MatchRealtimeOperation::class.java) ?: MatchRealtimeOperation.CREATED

    private fun anyEventId(): java.util.UUID = any(java.util.UUID::class.java) ?: java.util.UUID(0, 0)

    private fun <T : Any> matching(value: T): T = eq(value) ?: value

    companion object {
        private val EVENT_AT = Instant.parse("2026-09-02T10:00:00Z")
    }
}
