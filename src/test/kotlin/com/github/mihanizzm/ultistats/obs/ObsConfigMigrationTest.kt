package com.github.mihanizzm.ultistats.obs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import java.sql.DriverManager
import java.sql.SQLException

class ObsConfigMigrationTest {
    @Test fun `V7 creates exactly one default row and enforces singleton key`() {
        DriverManager.getConnection("jdbc:h2:mem:obs_migration;MODE=PostgreSQL").use { connection ->
            ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V7__obs_config.sql"))
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT payload FROM obs_config WHERE id = 1").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString(1)).isEqualTo("{}")
                    assertThat(rows.next()).isFalse()
                }
                assertThatThrownBy { sql.execute("INSERT INTO obs_config VALUES (2, '{}')") }.isInstanceOf(SQLException::class.java)
                assertThatThrownBy { sql.execute("INSERT INTO obs_config VALUES (1, '{}')") }.isInstanceOf(SQLException::class.java)
            }
        }
    }
}
