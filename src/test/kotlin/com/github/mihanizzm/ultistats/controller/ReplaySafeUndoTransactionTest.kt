package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.MatchAbstractTest
import com.github.mihanizzm.ultistats.dto.request.UndoEventRequest
import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.request.OnePlayerEventRequest
import com.github.mihanizzm.ultistats.dto.response.UndoCommandOutcome
import com.github.mihanizzm.ultistats.dto.response.realtime.MatchRealtimeOperation
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.UndoCommandReceipt
import com.github.mihanizzm.ultistats.model.events.*
import com.github.mihanizzm.ultistats.realtime.MatchEventStreamService
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataUndoCommandReceiptRepository
import com.github.mihanizzm.ultistats.service.result.*
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ReplaySafeUndoTransactionTest : MatchAbstractTest() {
    @Autowired lateinit var tx: TransactionTemplate
    @MockitoSpyBean lateinit var streams: MatchEventStreamService
    @MockitoSpyBean lateinit var receipts: SpringDataUndoCommandReceiptRepository
    private lateinit var match: Match
    private val at = Instant.parse("2026-10-24T10:00:00Z")

    @BeforeEach fun uniqueMatch() {
        match = Match(UUID.randomUUID(), listOf(TEAM_1.id, TEAM_2.id))
        matchService.create(match)
        assertIs<MatchCommandResult.Success<Match>>(matchService.startMatch(match.id, at))
        clearInvocations(streams, receipts)
    }

    @Test fun `concurrent replay emits one committed SSE and one receipt`() {
        val tail = point()
        val command = envelope(tail)
        clearInvocations(streams)
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        try {
            val futures = (1..4).map { pool.submit<UndoCommandResult> { check(gate.await(5, TimeUnit.SECONDS));eventService.undo(match.id, command) } }
            gate.countDown()
            val results = futures.map { assertIs<UndoCommandResult.Recorded>(it.get(10, TimeUnit.SECONDS)).response }
            assertEquals(1, results.distinct().size)
            assertEquals(4, results.first().resultLogRevision)
            assertEquals(1, receipts.findAll().count { it.matchId == match.id })
            verify(streams, after(150).times(1)).eventLogChanged(match.id, MatchRealtimeOperation.DELETED, tail.id)
            assertEquals(0, matchService.getOrThrow(match.id).teamScores.single { it.teamId == TEAM_1.id }.score)
        } finally { gate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun `rollback removes receipt score change revision and SSE then same ID can commit`() {
        val goal = point()
        val command = envelope(goal)
        clearInvocations(streams)
        tx.executeWithoutResult { status ->
            assertIs<UndoCommandResult.Recorded>(eventService.undo(match.id, command))
            assertEquals(0, matchService.getOrThrow(match.id).teamScores.single { it.teamId == TEAM_1.id }.score)
            status.setRollbackOnly()
        }
        assertNull(eventService.undoOutcome(match.id, command.commandId!!))
        assertEquals(goal, eventService.get(goal.id, match.id))
        assertEquals(3, eventService.snapshot(match.id)!!.logRevision)
        assertEquals(1, matchService.getOrThrow(match.id).teamScores.single { it.teamId == TEAM_1.id }.score)
        verify(streams, after(150).never()).eventLogChanged(match.id, MatchRealtimeOperation.DELETED, goal.id)
        val applied = assertIs<UndoCommandResult.Recorded>(eventService.undo(match.id, command)).response
        assertEquals(UndoCommandOutcome.APPLIED, applied.outcome)
        assertEquals(applied, eventService.undoOutcome(match.id, command.commandId!!))
        verify(streams, after(150).times(1)).eventLogChanged(match.id, MatchRealtimeOperation.DELETED, goal.id)
    }

    @Test fun `receipt write failure rolls back already performed deletion and projection`() {
        val goal = point()
        val command = envelope(goal)
        clearInvocations(streams)
        doThrow(IllegalStateException("synthetic receipt persistence failure")).`when`(receipts).saveAndFlush(any(UndoCommandReceipt::class.java) ?: dummyReceipt())
        try {
            assertFailsWith<IllegalStateException> { eventService.undo(match.id, command) }
        } finally { reset(receipts) }
        assertEquals(goal, eventService.get(goal.id, match.id))
        assertNull(eventService.undoOutcome(match.id, command.commandId!!))
        assertEquals(3, eventService.snapshot(match.id)!!.logRevision)
        assertEquals(1, matchService.getOrThrow(match.id).teamScores.single { it.teamId == TEAM_1.id }.score)
        verify(streams, after(150).never()).eventLogChanged(match.id, MatchRealtimeOperation.DELETED, goal.id)
        assertEquals(UndoCommandOutcome.APPLIED, assertIs<UndoCommandResult.Recorded>(eventService.undo(match.id, command)).response.outcome)
    }

    @Test fun `two different intents on observed tail yield one success and durable stale rejection`() {
        val tail = point()
        val first = envelope(tail)
        val second = first.copy(commandId = UUID.randomUUID())
        val outcomes = race({ eventService.undo(match.id, first) }, { eventService.undo(match.id, second) })
            .map { assertIs<UndoCommandResult.Recorded>(it).response }
        assertEquals(setOf(UndoCommandOutcome.APPLIED, UndoCommandOutcome.REJECTED), outcomes.map { it.outcome }.toSet())
        assertEquals(MatchProblemCode.EVENT_LOG_STALE, outcomes.single { it.rejection != null }.rejection!!.code)
        assertEquals(4, eventService.snapshot(match.id)!!.logRevision)
        assertEquals(2, eventService.getAllEventsOfMatch(match.id).size)
        outcomes.forEach { assertEquals(it, eventService.undoOutcome(match.id, it.commandId)) }
    }

    @Test fun `append racing guarded undo cannot remove an unobserved event`() {
        record(OnePlayerEvent(PLAYERS_2[0].id, at, EventType.PULL))
        val pickup = record(OnePlayerEvent(PLAYERS_1[0].id, at, EventType.PICKUP))
        val command = envelope(pickup)
        val passId = UUID.randomUUID()
        val outcomes = race({ eventService.undo(match.id, command) }, {
            eventService.create(TwoPlayerEvent(PLAYERS_1[0].id, PLAYERS_1[1].id, at, EventType.PASS), match.id, passId)
        })
        val undo = assertIs<UndoCommandResult.Recorded>(outcomes[0]).response
        if (undo.outcome == UndoCommandOutcome.APPLIED) {
            assertIs<EventCommandResult.Conflict>(outcomes[1])
            assertEquals(pickup.id, undo.removedEventId)
            assertEquals(1, eventService.getAllEventsOfMatch(match.id).size)
        } else {
            assertIs<EventCommandResult.Success>(outcomes[1])
            assertEquals(MatchProblemCode.EVENT_LOG_STALE, undo.rejection!!.code)
            assertEquals(listOf(pickup.id, passId), eventService.getAllEventsOfMatch(match.id).drop(1).map { it.id })
        }
        assertEquals(3, eventService.snapshot(match.id)!!.logRevision)
    }

    @Test fun `lookup serializes with command transaction and recovers committed receipt`() {
        val goal = point()
        val command = envelope(goal)
        val recorded = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val lookupStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit<UndoCommandResult> { tx.execute {
                val result = eventService.undo(match.id, command)
                recorded.countDown()
                check(commit.await(5, TimeUnit.SECONDS))
                result
            }!! }
            assertTrue(recorded.await(5, TimeUnit.SECONDS))
            val reader = pool.submit<com.github.mihanizzm.ultistats.dto.response.UndoCommandResponse?> { lookupStarted.countDown();eventService.undoOutcome(match.id,command.commandId!!) }
            assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { reader.get(100, TimeUnit.MILLISECONDS) }
            commit.countDown()
            val outcome = assertIs<UndoCommandResult.Recorded>(writer.get(5, TimeUnit.SECONDS)).response
            assertEquals(outcome, reader.get(5, TimeUnit.SECONDS))
        } finally { commit.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun `undo invalidates batch replay without invalidating undo receipt`() {
        val request = CreateEventBatchRequest(0, 0, listOf(OnePlayerEventRequest(EventType.PULL, at, PLAYERS_2[0].id, UUID.randomUUID())))
        val created = assertIs<EventBatchResult.Success>(eventService.appendBatch(match.id,request))
        val tail = eventService.getAllEventsOfMatch(match.id).single()
        val command = envelope(tail)
        val undo = assertIs<UndoCommandResult.Recorded>(eventService.undo(match.id,command)).response
        assertIs<EventBatchResult.Rejected>(eventService.appendBatch(match.id,request))
        assertEquals(undo,assertIs<UndoCommandResult.Recorded>(eventService.undo(match.id,command)).response)
        assertEquals(created.response.logRevision + 1,eventService.snapshot(match.id)!!.logRevision)
    }

    private fun race(first: () -> Any, second: () -> Any): List<Any> {
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val futures = listOf(first,second).map { action -> pool.submit<Any> { check(gate.await(5,TimeUnit.SECONDS));action() } }
            gate.countDown()
            return futures.map { it.get(10,TimeUnit.SECONDS) }
        } finally { gate.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)) }
    }
    private fun record(event: Event) = assertIs<EventCommandResult.Success>(eventService.create(event,match.id)).event
    private fun envelope(tail: StoredEvent) = UndoEventRequest(UUID.randomUUID(),tail.id,eventService.snapshot(match.id)!!.logRevision)
    private fun point(): StoredEvent {
        record(OnePlayerEvent(PLAYERS_2[0].id,at,EventType.PULL))
        record(OnePlayerEvent(PLAYERS_1[0].id,at,EventType.PICKUP))
        return record(TwoPlayerEvent(PLAYERS_1[0].id,PLAYERS_1[1].id,at,EventType.GOAL))
    }
    private fun dummyReceipt() = UndoCommandReceipt(matchId=match.id,commandId=UUID.randomUUID(),expectedLastEventId=UUID.randomUUID(),expectedLogRevision=0,resultJson="{}")
}
