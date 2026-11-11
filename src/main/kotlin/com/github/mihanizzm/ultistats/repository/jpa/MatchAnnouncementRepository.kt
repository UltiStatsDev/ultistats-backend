package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class MatchAnnouncementRepository(private val jdbc: JdbcTemplate) {
    private val columns = "id, team_id, started_second, started_nano, ended_second, ended_nano, original_team_id, attribution_revision"
    private val intervalMapper = RowMapper { row, _ ->
        MatchAnnouncement(
            id = row.getObject("id", UUID::class.java),
            teamId = row.getObject("team_id", UUID::class.java),
            startedAt = Instant.ofEpochSecond(row.getLong("started_second"), row.getLong("started_nano")),
            endedAt = row.getObject("ended_second", java.lang.Long::class.java)?.let {
                Instant.ofEpochSecond(it.toLong(), row.getLong("ended_nano"))
            },
            attributionRevision = row.getLong("attribution_revision"),
            originalTeamId = row.getObject("original_team_id", UUID::class.java),
        )
    }

    fun list(matchId: UUID): List<MatchAnnouncement> = jdbc.query(
        "SELECT $columns FROM match_announcements WHERE match_id = ? ORDER BY started_second, started_nano, id",
        intervalMapper, matchId,
    )

    fun find(matchId: UUID, id: UUID): MatchAnnouncement? = jdbc.query(
        "SELECT $columns FROM match_announcements WHERE match_id = ? AND id = ?",
        intervalMapper, matchId, id,
    ).singleOrNull()

    fun insert(matchId: UUID, value: MatchAnnouncement) {
        jdbc.update(
            """
            INSERT INTO match_announcements (match_id, id, team_id, started_second, started_nano, ended_second, ended_nano)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            matchId, value.id, value.teamId, value.startedAt.epochSecond, value.startedAt.nano,
            value.endedAt?.epochSecond, value.endedAt?.nano,
        )
    }

    fun end(matchId: UUID, id: UUID, endedAt: Instant) {
        jdbc.update(
            "UPDATE match_announcements SET ended_second = ?, ended_nano = ? WHERE match_id = ? AND id = ?",
            endedAt.epochSecond, endedAt.nano, matchId, id,
        )
    }

    /** Called with the match row locked. Captures the previous effective team on the first change. */
    fun correctTeam(matchId: UUID, id: UUID, teamId: UUID?) {
        jdbc.update(
            """
            UPDATE match_announcements
            SET original_team_id = CASE WHEN attribution_revision = 0 THEN team_id ELSE original_team_id END,
                team_id = ?, attribution_revision = attribution_revision + 1
            WHERE match_id = ? AND id = ?
            """.trimIndent(),
            teamId, matchId, id,
        )
    }
}
