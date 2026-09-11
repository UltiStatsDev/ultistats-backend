package com.github.mihanizzm.ultistats.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.DriverManager

class MatchParticipantMigrationTest {
    @Test
    fun `legacy match players and event references are migrated without data loss`() {
        DriverManager.getConnection(
            "jdbc:h2:mem:match_participant_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        ).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE teams (id UUID PRIMARY KEY)")
                sql.execute("CREATE TABLE players (id UUID PRIMARY KEY)")
                sql.execute("CREATE TABLE matches (id UUID PRIMARY KEY)")
                sql.execute("CREATE TABLE match_teams (match_id UUID NOT NULL, team_id UUID NOT NULL, PRIMARY KEY (match_id, team_id))")
                sql.execute(
                    """CREATE TABLE match_players (
                        match_id UUID NOT NULL,
                        player_id UUID NOT NULL,
                        team_id UUID NOT NULL,
                        number INTEGER,
                        PRIMARY KEY (match_id, player_id),
                        CONSTRAINT match_players_player_id_fkey FOREIGN KEY (player_id) REFERENCES players(id),
                        FOREIGN KEY (match_id, team_id) REFERENCES match_teams(match_id, team_id),
                        UNIQUE (match_id, team_id, number)
                    )""".trimIndent(),
                )
                sql.execute(
                    """CREATE TABLE events (
                        id UUID PRIMARY KEY,
                        match_id UUID NOT NULL,
                        from_player_id UUID,
                        to_player_id UUID,
                        CONSTRAINT events_from_player_fkey FOREIGN KEY (match_id, from_player_id)
                            REFERENCES match_players(match_id, player_id),
                        CONSTRAINT events_to_player_fkey FOREIGN KEY (match_id, to_player_id)
                            REFERENCES match_players(match_id, player_id)
                    )""".trimIndent(),
                )
                sql.execute("INSERT INTO teams VALUES ('00000000-0000-0000-0000-000000000001')")
                sql.execute("INSERT INTO players VALUES ('00000000-0000-0000-0000-000000000010')")
                sql.execute("INSERT INTO matches VALUES ('00000000-0000-0000-0000-000000000100')")
                sql.execute("INSERT INTO match_teams VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000001')")
                sql.execute("INSERT INTO match_players VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 7)")
                sql.execute("INSERT INTO events VALUES ('00000000-0000-0000-0000-000000000200', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000010', NULL)")
            }

            ScriptUtils.executeSqlScript(
                connection,
                ClassPathResource("db/migration/V1_1__migrate_match_players_to_participants.sql"),
            )

            connection.createStatement().use { sql ->
                sql.execute(
                    """INSERT INTO match_participants
                        (match_id, participant_id, team_id, kind, unknown_slot, number)
                        VALUES (
                            '00000000-0000-0000-0000-000000000100',
                            '00000000-0000-0000-0000-000000000099',
                            '00000000-0000-0000-0000-000000000001',
                            'UNKNOWN',
                            1,
                            NULL
                        )""".trimIndent(),
                )
                sql.executeQuery(
                    "SELECT participant_id, team_id, kind, unknown_slot, number FROM match_participants ORDER BY kind",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("participant_id")).isEqualTo("00000000-0000-0000-0000-000000000010")
                    assertThat(rows.getString("team_id")).isEqualTo("00000000-0000-0000-0000-000000000001")
                    assertThat(rows.getString("kind")).isEqualTo("PLAYER")
                    assertThat(rows.getObject("unknown_slot")).isNull()
                    assertThat(rows.getInt("number")).isEqualTo(7)
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("participant_id")).isEqualTo("00000000-0000-0000-0000-000000000099")
                    assertThat(rows.getString("kind")).isEqualTo("UNKNOWN")
                    assertThat(rows.getInt("unknown_slot")).isEqualTo(1)
                }
                sql.executeQuery("SELECT from_participant_id, to_participant_id FROM events").use { row ->
                    assertThat(row.next()).isTrue()
                    assertThat(row.getString("from_participant_id")).isEqualTo("00000000-0000-0000-0000-000000000010")
                    assertThat(row.getObject("to_participant_id")).isNull()
                }
            }
        }
    }
}
