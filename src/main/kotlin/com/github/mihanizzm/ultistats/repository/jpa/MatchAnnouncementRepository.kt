package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class MatchAnnouncementRepository(private val jdbc: JdbcTemplate) {
    fun list(matchId: UUID): List<MatchAnnouncement> = jdbc.query(
        """
        SELECT id, team_id, started_second, started_nano, ended_second, ended_nano
        FROM match_announcements WHERE match_id = ? ORDER BY started_second, started_nano, id
        """.trimIndent(),
        { row, _ ->
            MatchAnnouncement(
                id = row.getObject("id", UUID::class.java),
                teamId = row.getObject("team_id", UUID::class.java),
                startedAt = Instant.ofEpochSecond(row.getLong("started_second"), row.getLong("started_nano")),
                endedAt = row.getObject("ended_second", java.lang.Long::class.java)?.let {
                    Instant.ofEpochSecond(it.toLong(), row.getLong("ended_nano"))
                },
            )
        },
        matchId,
    )

    fun insert(matchId: UUID, value: MatchAnnouncement) {
        jdbc.update(
            "INSERT INTO match_announcements (match_id, id, team_id, started_second, started_nano) VALUES (?, ?, ?, ?, ?)",
            matchId, value.id, value.teamId, value.startedAt.epochSecond, value.startedAt.nano,
        )
    }

    fun end(matchId: UUID, id: UUID, endedAt: Instant) {
        jdbc.update(
            "UPDATE match_announcements SET ended_second = ?, ended_nano = ? WHERE match_id = ? AND id = ?",
            endedAt.epochSecond, endedAt.nano, matchId, id,
        )
    }
}
