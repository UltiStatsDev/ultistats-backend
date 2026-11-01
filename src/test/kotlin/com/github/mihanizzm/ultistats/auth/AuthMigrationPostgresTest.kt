package com.github.mihanizzm.ultistats.auth

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@EnabledIfEnvironmentVariable(named="TEST_POSTGRES_URL",matches=".+")
class AuthMigrationPostgresTest {
    @Test fun `actual V9 baseline passes V10 then V11 without rewriting sports or installation on restart`() {
        val schema="auth152_upgrade_"+UUID.randomUUID().toString().replace("-","")
        val url=System.getenv("TEST_POSTGRES_URL");val user=System.getenv("TEST_POSTGRES_USERNAME");val password=System.getenv("TEST_POSTGRES_PASSWORD")
        fun flyway(target:String)=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target(target).load()
        DriverManager.getConnection(url,user,password).use { connection ->
            try {
                flyway("9").migrate()
                connection.createStatement().use { sql ->
                    sql.execute("SET search_path TO $schema")
                    sql.execute("""
                        INSERT INTO teams(id,name,photo_url) VALUES ('00000000-0000-0000-0000-000000000152','Legacy synthetic team','/uploads/legacy-team.png');
                        INSERT INTO players(id,first_name,last_name,photo_url) VALUES ('00000000-0000-0000-0000-000000000153','Legacy','Player','/uploads/legacy-player.png');
                        INSERT INTO team_players(team_id,player_id,number) VALUES ('00000000-0000-0000-0000-000000000152','00000000-0000-0000-0000-000000000153',7);
                        INSERT INTO matches(id,event_log_revision,last_event_log_invalidation_revision,started_at,ended_at) VALUES ('00000000-0000-0000-0000-000000000154',3,2,'2026-07-28T12:00:00.123457Z','2026-07-28T12:00:01.123457Z');
                        INSERT INTO events(id,match_id,sequence_number,event_type,occurred_at,creation_fingerprint) VALUES ('00000000-0000-0000-0000-000000000155','00000000-0000-0000-0000-000000000154',7,'HALFTIME_START','2026-07-28T12:00:00.123457Z',repeat('a',64));
                        INSERT INTO event_batch_receipts(id,match_id,base_sequence_number,base_log_revision,fingerprint) VALUES ('00000000-0000-0000-0000-000000000156','00000000-0000-0000-0000-000000000154',6,2,repeat('b',64));
                        INSERT INTO undo_command_receipts(id,match_id,command_id,expected_last_event_id,expected_log_revision,result_json) VALUES ('00000000-0000-0000-0000-000000000157','00000000-0000-0000-0000-000000000154','00000000-0000-0000-0000-000000000158','00000000-0000-0000-0000-000000000155',3,'{"legacy":true}');
                        UPDATE obs_config SET payload='{"legacy":"retained"}' WHERE id=1;
                        INSERT INTO obs_scoreboard_profiles(match_id,payload) VALUES ('00000000-0000-0000-0000-000000000154','{"title":"Legacy profile"}');
                    """.trimIndent())
                }
                val tables=listOf("teams","players","team_players","matches","events","event_batch_receipts","undo_command_receipts","obs_config","obs_scoreboard_profiles")
                fun snapshot(table:String,stripPrecision:Boolean=false):String=connection.createStatement().use { sql ->
                    val value=if(stripPrecision) "to_jsonb(t)-'original_occurred_at'-'original_started_at_second'-'original_started_at_nano'-'original_ended_at_second'-'original_ended_at_nano'" else "to_jsonb(t)"
                    sql.executeQuery("SELECT $value AS data FROM $table t ORDER BY 1").use { rows -> buildList { while(rows.next()) add(rows.getString(1)) }.joinToString("\n") }
                }
                val legacy=tables.associateWith { snapshot(it) }
                flyway("10").migrate()
                tables.forEach { assertEquals(legacy[it],snapshot(it,true),it) }
                val precise=tables.associateWith { snapshot(it) }
                flyway("11").migrate()
                tables.forEach { assertEquals(precise[it],snapshot(it),it) }
                val installation=connection.createStatement().executeQuery("SELECT installation_id FROM auth_installation").use { rows -> rows.next();rows.getObject(1,UUID::class.java) }
                assertNotNull(installation)
                flyway("11").validate()
                DriverManager.getConnection(url,user,password).use { restarted ->
                    restarted.createStatement().executeQuery("SELECT installation_id FROM $schema.auth_installation").use { rows -> rows.next();assertEquals(installation,rows.getObject(1,UUID::class.java)) }
                }
                assertEquals(0,connection.createStatement().executeQuery("SELECT count(*) FROM auth_accounts").use { it.next();it.getInt(1) })
            } finally { connection.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        }
    }
}
