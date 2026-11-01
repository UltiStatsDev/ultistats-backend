package com.github.mihanizzm.ultistats.migration

import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.events.EventType
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class EventPrecisionPostgresMigrationTest {
    @Test
    fun `V9 seed upgrades without rewriting identity positions fingerprints or receipts and old writers still work`() {
        val url = System.getenv("TEST_POSTGRES_URL")
        val user = System.getenv("TEST_POSTGRES_USERNAME")
        val password = System.getenv("TEST_POSTGRES_PASSWORD")
        val schema = "batch71_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        fun flyway(target: String) = Flyway.configure().dataSource(url, user, password)
            .schemas(schema).defaultSchema(schema).target(target).load()
        DriverManager.getConnection(url, user, password).use { connection ->
            try {
                flyway("9").migrate()
                connection.createStatement().use { sql ->
                    sql.execute("SET search_path TO $schema")
                    sql.execute("""
                        INSERT INTO matches(id,event_log_revision,last_event_log_invalidation_revision,started_at,ended_at)
                        VALUES ('00000000-0000-0000-0000-000000000071',3,2,'2026-07-28T12:00:00.123457Z','2026-07-28T12:00:01.123457Z');
                        INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,creation_fingerprint)
                        VALUES ('00000000-0000-0000-0000-000000000072','00000000-0000-0000-0000-000000000071',7,
                        'HALFTIME_START','2026-07-28T12:00:00.123457Z',repeat('a',64));
                        INSERT INTO event_batch_receipts(id,match_id,base_sequence_number,base_log_revision,fingerprint)
                        VALUES ('00000000-0000-0000-0000-000000000073','00000000-0000-0000-0000-000000000071',6,2,repeat('b',64));
                        INSERT INTO undo_command_receipts(id,match_id,command_id,expected_last_event_id,expected_log_revision,result_json)
                        VALUES ('00000000-0000-0000-0000-000000000074','00000000-0000-0000-0000-000000000071',
                        '00000000-0000-0000-0000-000000000075','00000000-0000-0000-0000-000000000072',3,'{"legacy":true}');
                    """.trimIndent())
                }
                fun snapshot(table: String, projection: String = "*"): String = connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT row_to_json(t)::text FROM (SELECT $projection FROM $table ORDER BY id) t").use {
                        buildList { while (it.next()) add(it.getString(1)) }.joinToString("\n")
                    }
                }
                val tables = listOf("events", "matches", "event_batch_receipts", "undo_command_receipts")
                val before = tables.associateWith { snapshot(it) }
                flyway("10").migrate()
                tables.forEach { table ->
                    val actual = if (table == "events" || table == "matches") connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT (to_jsonb(e) - 'original_occurred_at' - 'original_started_at_second' - 'original_started_at_nano' - 'original_ended_at_second' - 'original_ended_at_nano')::text FROM $table e ORDER BY id").use { rows ->
                            rows.next(); rows.getString(1)
                        }
                    } else snapshot(table)
                    // JSON object key order differs between json/jsonb; compare the actual data.
                    val mapper = com.fasterxml.jackson.databind.ObjectMapper()
                    assertEquals(mapper.readTree(before.getValue(table)), mapper.readTree(actual), table)
                }
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT original_occurred_at FROM events").use { rows -> rows.next(); assertNull(rows.getString(1)) }
                    sql.executeQuery("SELECT original_started_at_second, original_started_at_nano, original_ended_at_second, original_ended_at_nano FROM matches").use { rows ->
                        rows.next(); (1..4).forEach { assertNull(rows.getString(it)) }
                    }
                    // An old binary's INSERT omits the additive column. Its UPDATE keeps occurredAt immutable.
                    sql.execute("""INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at)
                        VALUES ('00000000-0000-0000-0000-000000000076','00000000-0000-0000-0000-000000000071',8,
                        'HALFTIME_END','2026-07-28T12:00:01Z')""")
                    sql.execute("UPDATE events SET creation_fingerprint = NULL WHERE sequence_number = 7")
                    sql.executeQuery("SELECT count(*) FROM events WHERE original_occurred_at IS NULL").use { rows ->
                        rows.next(); assertEquals(2, rows.getInt(1))
                    }
                }
                // Check the actual PG JDBC rounding used by Hibernate, including the half/carry/negative cases.
                listOf(
                    "2026-07-28T12:00:00.123456500Z" to "2026-07-28T12:00:00.123457Z",
                    "2026-07-28T12:00:00.999999789Z" to "2026-07-28T12:00:01Z",
                    "1960-01-01T12:00:00.123456500Z" to "1960-01-01T12:00:00.123457Z",
                    "1969-12-31T23:59:59.999999500Z" to "1970-01-01T00:00:00Z",
                ).forEach { (original, expectedStored) ->
                    connection.prepareStatement("SELECT ?::timestamptz").use { sql ->
                        sql.setTimestamp(1, Timestamp.from(Instant.parse(original)))
                        sql.executeQuery().use { rows ->
                            rows.next()
                            val stored = rows.getTimestamp(1).toInstant()
                            assertEquals(Instant.parse(expectedStored), stored)
                            val event = EventEntity(UUID.randomUUID(), UUID.randomUUID(), 1, EventType.HALFTIME_START,
                                stored, originalOccurredAt = original)
                            assertEquals(Instant.parse(original), event.toDomain().occurredAt)
                        }
                    }
                }
            } finally {
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }
}
