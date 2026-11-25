package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.JsonNode
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.sql.Timestamp

@Service
class ObsInfographicsSettingsService(private val jdbc: JdbcTemplate, private val clock: Clock) {
    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ -> ObsInfographicsSettings(
        rs.getInt("roster_page_seconds"), rs.getInt("point_page_seconds"), rs.getInt("leader_page_seconds"), rs.getLong("revision"),
    ) }

    fun get(): ObsInfographicsSettings = jdbc.query("SELECT * FROM obs_infographics_settings WHERE id = 1", mapper).single()

    // A read never creates history. Old phases retain the real pre-settings default.
    fun at(epoch: Instant?): ObsInfographicsSettings = epoch?.let {
        jdbc.query("SELECT * FROM obs_infographics_settings_history WHERE effective_at <= ? ORDER BY revision DESC LIMIT 1", mapper, Timestamp.from(it)).firstOrNull()
    } ?: ObsInfographicsSettings()

    @Transactional
    fun save(body: JsonNode): ObsInfographicsSettings {
        val fields = listOf("rosterPageSeconds", "pointPageSeconds", "leaderPageSeconds")
        if (!body.isObject || fields.any { name ->
            val value = body.get(name)
            value == null || !value.isIntegralNumber || !value.canConvertToInt() || value.intValue() !in 2..15
        }) throw InvalidPatchException("Infographics intervals must be JSON integers from 2 to 15; all three fields are required")
        val current = jdbc.query("SELECT * FROM obs_infographics_settings WHERE id = 1 FOR UPDATE", mapper).single()
        val next = ObsInfographicsSettings(body[fields[0]].intValue(), body[fields[1]].intValue(), body[fields[2]].intValue(), current.revision)
        if (next == current) return current
        val changed = next.copy(revision = Math.addExact(current.revision, 1))
        jdbc.update("UPDATE obs_infographics_settings SET roster_page_seconds=?, point_page_seconds=?, leader_page_seconds=?, revision=? WHERE id=1",
            changed.rosterPageSeconds, changed.pointPageSeconds, changed.leaderPageSeconds, changed.revision)
        jdbc.update("INSERT INTO obs_infographics_settings_history(revision,effective_at,roster_page_seconds,point_page_seconds,leader_page_seconds) VALUES (?,?,?,?,?)",
            changed.revision, Timestamp.from(clock.instant()), changed.rosterPageSeconds, changed.pointPageSeconds, changed.leaderPageSeconds)
        return changed
    }
}
