package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class ObsConfigChanged

@Service
class ObsConfigService(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
    private val publisher: ApplicationEventPublisher,
) {
    fun get(): ObsConfig = jdbc.query("SELECT payload FROM obs_config WHERE id = 1") { rs, _ ->
        json.readValue(rs.getString(1), ObsConfig::class.java)
    }.firstOrNull() ?: ObsConfig()

    @Transactional
    fun save(config: ObsConfig): ObsConfig {
        if (config.teamColors.values.any { !it.matches(Regex("#[0-9a-fA-F]{6}")) }) {
            throw InvalidPatchException("teamColors values must use #RRGGBB")
        }
        // The migration creates the singleton, so concurrent saves serialize on this row.
        jdbc.update("UPDATE obs_config SET payload = ? WHERE id = 1", json.writeValueAsString(config))
        publisher.publishEvent(ObsConfigChanged())
        return config
    }
}
