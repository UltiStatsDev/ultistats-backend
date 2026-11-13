package com.github.mihanizzm.ultistats.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class IncomingReservePostgresMigrationTest {
    @Test
    fun `V16 preserves V15 confirmed lineup and raw actor journal`() {
        val url = System.getenv("TEST_POSTGRES_URL")
        val user = System.getenv("TEST_POSTGRES_USERNAME")
        val password = System.getenv("TEST_POSTGRES_PASSWORD")
        val schema = "reserve216_" + UUID.randomUUID().toString().replace("-", "")
        fun flyway(target: String) = Flyway.configure().dataSource(url, user, password)
            .schemas(schema).defaultSchema(schema).target(target).load()

        val (match, point, team, slot, pull) = List(5) { UUID.randomUUID() }
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { sql ->
                try {
                    flyway("15").migrate()
                    sql.execute("SET search_path TO $schema")
                    sql.execute("""
                        INSERT INTO teams(id, name) VALUES ('$team', 'V15 team');
                        INSERT INTO matches(id, event_log_revision) VALUES ('$match', 1);
                        INSERT INTO match_teams(match_id, team_id, team_name, position) VALUES ('$match', '$team', 'V15 team', 1);
                        INSERT INTO match_point_lineups(match_id, point_id, revision) VALUES ('$match', '$point', 1);
                        INSERT INTO match_point_lineup_entries(match_id, point_id, team_id, position, slot_id, slot_number)
                            VALUES ('$match', '$point', '$team', 0, '$slot', 1);
                        INSERT INTO events(id, match_id, sequence_number, event_type, occurred_at, from_slot_id, creation_fingerprint)
                            VALUES ('$pull', '$match', 1, 'PULL', '2026-10-15T09:00:00Z', '$slot', repeat('a', 64));
                    """.trimIndent())
                    fun snapshot(table: String, stripIntroduction: Boolean = false): String {
                        val projection = "to_jsonb(t)" + if (stripIntroduction) " - 'introduced_by_event_id'" else ""
                        return sql.executeQuery("SELECT ($projection)::text FROM $table t").use { rows ->
                            check(rows.next())
                            rows.getString(1).also { check(!rows.next()) }
                        }
                    }
                    val lineup = snapshot("match_point_lineup_entries")
                    val event = snapshot("events")
                    flyway("16").migrate()
                    assertEquals(lineup, snapshot("match_point_lineup_entries", stripIntroduction = true))
                    assertEquals(event, snapshot("events"))
                    sql.executeQuery("SELECT introduced_by_event_id FROM match_point_lineup_entries").use {
                        check(it.next())
                        assertNull(it.getObject(1))
                    }
                    flyway("16").validate()
                } finally {
                    sql.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }
}
