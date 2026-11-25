package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class ObsConfigChanged

@Service
class ObsConfigService(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
    private val publisher: ApplicationEventPublisher,
) {
    fun get(): ObsConfig = read(lock = false)

    private fun read(lock: Boolean): ObsConfig {
        val statement = "SELECT payload FROM obs_config WHERE id = 1" + if (lock) " FOR UPDATE" else ""
        return jdbc.query(statement) { rs, _ -> json.readValue(rs.getString(1), ObsConfig::class.java) }
            .firstOrNull() ?: ObsConfig()
    }

    @Transactional
    fun save(config: ObsConfig, matchId: UUID? = null, preserveTeamColors: Boolean = false): ObsConfig {
        if (config.teamColors.values.any { !it.matches(Regex("#[0-9a-fA-F]{6}")) }) {
            throw InvalidPatchException("teamColors values must use #RRGGBB")
        }
        if (preserveTeamColors && (matchId != null || config.teamColors.isNotEmpty())) {
            throw InvalidPatchException("preserveTeamColors requires no matchId and no team color overrides")
        }

        val colors = when {
            matchId != null -> {
                val pair = matchTeamIds(matchId)
                if (!pair.containsAll(config.teamColors.keys)) {
                    throw InvalidPatchException("Event Log overrides must reference teams of this match")
                }
                // Pair replacement and global saves serialize on the same singleton row.
                read(lock = true).teamColors - pair + config.teamColors
            }
            preserveTeamColors -> read(lock = true).teamColors
            else -> config.teamColors
        }
        if (colors.size > 500) throw InvalidPatchException("teamColors supports up to 500 overrides")

        val updated = config.copy(teamColors = colors)
        jdbc.update("UPDATE obs_config SET payload = ? WHERE id = 1", json.writeValueAsString(updated))
        publisher.publishEvent(ObsConfigChanged())
        return updated
    }

    private fun matchTeamIds(matchId: UUID): Set<UUID> {
        // Take the match row lock before reading team membership, as in scoreboard profile writes.
        val exists = jdbc.query(
            "SELECT id FROM matches WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId,
        ).isNotEmpty()
        if (!exists) throw EntityNotFoundException("Match $matchId not found")
        val teamIds = jdbc.query(
            "SELECT team_id FROM match_teams WHERE match_id = ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId,
        ).toSet()
        if (teamIds.size != 2) throw InvalidPatchException("Event Log colors require two teams of this match")
        return teamIds
    }
}
