package com.github.mihanizzm.ultistats.obs

import org.springframework.jdbc.core.JdbcTemplate

/** Focused setup/checker for config persistence; controller tests exercise the public API. */
class ObsDatabaseHelper(private val jdbc: JdbcTemplate) {
    fun resetConfig() { jdbc.update("UPDATE obs_config SET payload = '{}' WHERE id = 1") }
    fun persistedConfig(): String = jdbc.queryForObject("SELECT payload FROM obs_config WHERE id = 1", String::class.java)!!
    fun gameState(): List<Map<String, Any>> = listOf("events", "matches", "match_teams").flatMap { table ->
        jdbc.queryForList("SELECT * FROM $table")
    }
}
