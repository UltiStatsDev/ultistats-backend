package com.github.mihanizzm.ultistats.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.DriverManager
import java.sql.SQLException

class MatchPlayersPerTeamMigrationTest {
    @Test
    fun `V4 assigns seven to existing matches and enforces minimum`() {
        DriverManager.getConnection(
            "jdbc:h2:mem:match_players_per_team_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        ).use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE TABLE matches (id UUID PRIMARY KEY)")
                sql.execute("INSERT INTO matches (id) VALUES ('00000000-0000-0000-0000-000000000100')")
            }

            ScriptUtils.executeSqlScript(
                connection,
                ClassPathResource("db/migration/V4__add_players_per_team_to_matches.sql"),
            )

            connection.createStatement().use { sql ->
                sql.execute("INSERT INTO matches (id) VALUES ('00000000-0000-0000-0000-000000000101')")
                sql.executeQuery("SELECT players_per_team FROM matches ORDER BY id").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt("players_per_team")).isEqualTo(7)
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt("players_per_team")).isEqualTo(7)
                }
                assertThatThrownBy {
                    sql.execute("UPDATE matches SET players_per_team = 1")
                }.isInstanceOf(SQLException::class.java)
                sql.execute("UPDATE matches SET players_per_team = 2")
            }
        }
    }
}
