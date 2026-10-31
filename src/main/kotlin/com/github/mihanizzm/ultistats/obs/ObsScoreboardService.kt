package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import com.github.mihanizzm.ultistats.model.MatchStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ObsScoreboardService(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
    private val publisher: ApplicationEventPublisher,
) {
    // Multiple reads must describe one committed match, including concurrent score/finish changes.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun snapshot(matchId: UUID): ObsScoreboardSnapshot {
        val times = jdbc.query("SELECT started_at, ended_at FROM matches WHERE id = ? AND deleted_at IS NULL",
            { rs, _ -> rs.getTimestamp(1)?.toInstant() to rs.getTimestamp(2)?.toInstant() }, matchId)
            .firstOrNull() ?: throw EntityNotFoundException("Match $matchId not found")
        val teams = jdbc.query("""
            SELECT mt.team_id, mt.team_name, t.photo_url, mt.score
            FROM match_teams mt JOIN teams t ON t.id = mt.team_id
            WHERE mt.match_id = ? ORDER BY mt.position
        """.trimIndent(), { rs, _ ->
            ObsScoreboardTeam(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4))
        }, matchId)
        val profile = jdbc.query("SELECT payload FROM obs_scoreboard_profiles WHERE match_id = ?",
            { rs, _ -> json.readValue(rs.getString(1), ObsScoreboardProfile::class.java) }, matchId)
            .firstOrNull() ?: ObsScoreboardProfile()
        return ObsScoreboardSnapshot(matchId, teams, when {
            times.second != null -> MatchStatus.FINISHED
            times.first != null -> MatchStatus.IN_PROGRESS
            else -> MatchStatus.PLANNED
        }, times.first, times.second, Instant.now(), profile)
    }

    @Transactional
    fun save(matchId: UUID, profile: ObsScoreboardProfile): ObsScoreboardProfile {
        // Same row lock as match team/lifecycle commands; also serializes first profile inserts.
        val exists = jdbc.query("SELECT id FROM matches WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId).isNotEmpty()
        if (!exists) throw EntityNotFoundException("Match $matchId not found")
        val teamIds = jdbc.query("SELECT team_id FROM match_teams WHERE match_id = ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, matchId).toSet()
        if ((profile.shortNames.keys + profile.teamColors.keys).any { it !in teamIds }) {
            throw InvalidPatchException("Scoreboard overrides must reference teams of this match")
        }
        if (profile.shortNames.values.any { it.isBlank() || it.length > 80 || it.any(Char::isISOControl) }) {
            throw InvalidPatchException("Scoreboard short names must contain 1–80 characters without control characters")
        }
        if (profile.teamColors.values.any { !it.matches(Regex("#[0-9a-fA-F]{6}")) }) {
            throw InvalidPatchException("Scoreboard colors must use #RRGGBB")
        }
        val payload = json.writeValueAsString(profile)
        if (jdbc.update("UPDATE obs_scoreboard_profiles SET payload = ? WHERE match_id = ?", payload, matchId) == 0) {
            jdbc.update("INSERT INTO obs_scoreboard_profiles (match_id, payload) VALUES (?, ?)", matchId, payload)
        }
        publisher.publishEvent(ScoreboardProfileChanged(matchId))
        return profile
    }
}
