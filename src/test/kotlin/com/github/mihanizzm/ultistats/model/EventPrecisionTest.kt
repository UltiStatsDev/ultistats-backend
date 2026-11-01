package com.github.mihanizzm.ultistats.model

import com.github.mihanizzm.ultistats.model.events.EventType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class EventPrecisionTest {
    @Test
    fun `match boundaries restore only trustworthy known precision`() {
        val start = Instant.parse("2026-07-28T12:00:00.123457Z")
        val end = Instant.parse("2026-07-28T12:00:01Z")
        val legacy = Match(UUID.randomUUID(), emptyList(), startedAt = start, endedAt = end,
            originalStartedAtSecond = null, originalStartedAtNano = null, originalEndedAtSecond = null, originalEndedAtNano = null)
        assertEquals(start, legacy.effectiveStartedAt)
        assertEquals(end, legacy.effectiveEndedAt)
        val exact = legacy.copy(originalStartedAtSecond = start.epochSecond, originalStartedAtNano = 123456789,
            originalEndedAtSecond = start.epochSecond, originalEndedAtNano = 999999789)
        assertEquals(Instant.parse("2026-07-28T12:00:00.123456789Z"), exact.effectiveStartedAt)
        assertEquals(Instant.parse("2026-07-28T12:00:00.999999789Z"), exact.effectiveEndedAt)
        val stale = exact.copy(startedAt = start.plusSeconds(1), endedAt = end.plusSeconds(1))
        assertEquals(start.plusSeconds(1), stale.effectiveStartedAt)
        assertEquals(end.plusSeconds(1), stale.effectiveEndedAt)
        assertEquals(null, exact.copy(startedAt = null).effectiveStartedAt)
        assertEquals(start, exact.copy(originalStartedAtSecond = null).effectiveStartedAt)
        assertEquals(start, exact.copy(originalStartedAtNano = null).effectiveStartedAt)
        assertEquals(start, exact.copy(originalStartedAtNano = -1).effectiveStartedAt)
        assertEquals(start, exact.copy(originalStartedAtNano = 1000000000).effectiveStartedAt)
        assertEquals(start, exact.copy(originalStartedAtSecond = Long.MAX_VALUE).effectiveStartedAt)
    }

    @Test
    fun `legacy and stale metadata use known stored time without fabricating nanos`() {
        val stored = Instant.parse("2026-07-28T12:00:00.123457Z")
        val legacy = EventEntity(UUID.randomUUID(), UUID.randomUUID(), 1, EventType.HALFTIME_START,
            stored, originalOccurredAt = null)
        assertEquals(stored, legacy.toDomain().occurredAt)
        assertEquals(stored, legacy.copy(originalOccurredAt = "2026-07-28T12:00:01.123456789Z").toDomain().occurredAt)
        assertEquals(stored, legacy.copy(originalOccurredAt = "invalid").toDomain().occurredAt)
        assertEquals(Instant.parse("2026-07-28T12:00:00.123456789Z"),
            legacy.copy(originalOccurredAt = "2026-07-28T12:00:00.123456789Z").toDomain().occurredAt)
    }

    @Test
    fun `rounding guard handles carry to next second and pre epoch times`() {
        listOf(
            "2026-07-28T12:00:00.999999789Z" to "2026-07-28T12:00:01Z",
            "1960-01-01T12:00:00.123456500Z" to "1960-01-01T12:00:00.123457Z",
            "1969-12-31T23:59:59.999999500Z" to "1970-01-01T00:00:00Z",
        ).forEach { (original, stored) ->
            val row = EventEntity(UUID.randomUUID(), UUID.randomUUID(), 1, EventType.HALFTIME_START,
                Instant.parse(stored), originalOccurredAt = original)
            assertEquals(Instant.parse(original), row.toDomain().occurredAt)
        }
    }
}
