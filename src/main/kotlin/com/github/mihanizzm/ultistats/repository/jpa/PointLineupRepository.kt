package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupEntry
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class PointLineupRepository(private val jdbc: JdbcTemplate) {
    fun exists(matchId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM match_point_lineups WHERE match_id = ?)", Boolean::class.java, matchId,
    ) == true

    fun list(matchId: UUID, teamIds: List<UUID>): Map<UUID, PointLineup> = jdbc.query(
        """
        SELECT l.point_id, l.revision, e.team_id, e.participant_id, e.slot_id, e.slot_number, e.resolved_participant_id
        FROM match_point_lineups l
        LEFT JOIN match_point_lineup_entries e ON l.match_id = e.match_id AND l.point_id = e.point_id
        WHERE l.match_id = ?
        ORDER BY l.point_id, e.team_id, e.position
        """.trimIndent(),
        { row, _ ->
            LineupRow(
                requireNotNull(row.uuid("point_id")), row.getLong("revision"), row.uuid("team_id"),
                row.uuid("participant_id")?.let(::PointLineupPlayer)
                    ?: row.uuid("slot_id")?.let { PointLineupTemporary(it, row.getInt("slot_number"), row.uuid("resolved_participant_id")) },
            )
        },
        matchId,
    ).groupBy { it.pointId }.mapValues { (_, rows) ->
        val byTeam = rows.groupBy { it.teamId }
        PointLineup(rows.first().revision, teamIds.map { teamId ->
            PointLineupTeam(teamId, byTeam[teamId].orEmpty().mapNotNull { it.entry })
        })
    }

    /** The caller holds the match lock and supplies the canonical opening boundary. */
    fun insert(matchId: UUID, point: PointBoundary, lineup: PointLineup) {
        jdbc.update(
            "INSERT INTO match_point_lineups (match_id, point_id, opening_event_id, revision) VALUES (?, ?, ?, ?)",
            matchId, point.pointId, point.openingEventId, lineup.revision,
        )
        val rows = lineup.teams.flatMap { team ->
            team.entries.mapIndexed { index, entry ->
                val player = entry as? PointLineupPlayer
                val temporary = entry as? PointLineupTemporary
                arrayOf<Any?>(matchId, point.pointId, team.teamId, index, player?.participantId,
                    temporary?.slotId, temporary?.number, temporary?.resolvedParticipantId)
            }
        }
        if (rows.isNotEmpty()) jdbc.batchUpdate(
            """
            INSERT INTO match_point_lineup_entries
                (match_id, point_id, team_id, position, participant_id, slot_id, slot_number, resolved_participant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            rows,
        )
    }

    private data class LineupRow(val pointId: UUID, val revision: Long, val teamId: UUID?, val entry: PointLineupEntry?)
    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)
}
