package com.github.mihanizzm.ultistats.model

import com.github.mihanizzm.ultistats.model.events.EventType
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class EventPrecisionTest {
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
        ).forEach { (original, stored) ->
            val row = EventEntity(UUID.randomUUID(), UUID.randomUUID(), 1, EventType.HALFTIME_START,
                Instant.parse(stored), originalOccurredAt = original)
            assertEquals(Instant.parse(original), row.toDomain().occurredAt)
        }
    }
}
