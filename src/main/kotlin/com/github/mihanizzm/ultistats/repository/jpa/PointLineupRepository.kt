package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupEntry
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
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

    fun slots(matchId: UUID): Map<UUID, PointLineupSlotActor> = jdbc.query(
        """SELECT e.slot_id, e.point_id, e.team_id, e.resolved_participant_id
            FROM match_point_lineup_entries e
            LEFT JOIN events intro ON intro.match_id = e.match_id AND intro.id = e.introduced_by_event_id
            WHERE e.match_id = ? AND e.slot_id IS NOT NULL AND intro.deleted_at IS NULL""",
        { row, _ ->
            requireNotNull(row.uuid("slot_id")) to PointLineupSlotActor(
                requireNotNull(row.uuid("point_id")), requireNotNull(row.uuid("team_id")), row.uuid("resolved_participant_id"),
            )
        },
        matchId,
    ).toMap()

    fun list(matchId: UUID, teamIds: List<UUID>): Map<UUID, PointLineup> = jdbc.query(
        """
        SELECT l.point_id, l.revision, e.team_id, e.participant_id, e.slot_id, e.slot_number, e.resolved_participant_id
        FROM match_point_lineups l
        LEFT JOIN match_point_lineup_entries e ON l.match_id = e.match_id AND l.point_id = e.point_id
            AND e.introduced_by_event_id IS NULL
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

    fun introducedSlots(matchId: UUID): List<IntroducedPointSlot> = jdbc.query(
        """SELECT point_id, team_id, slot_id, slot_number, resolved_participant_id, introduced_by_event_id
            FROM match_point_lineup_entries WHERE match_id = ? AND introduced_by_event_id IS NOT NULL
            ORDER BY point_id, team_id, slot_number""",
        { row, _ ->
            IntroducedPointSlot(requireNotNull(row.uuid("point_id")), PointSubstitutionSlot(
                requireNotNull(row.uuid("team_id")), requireNotNull(row.uuid("slot_id")),
                row.getInt("slot_number"), row.uuid("resolved_participant_id"), requireNotNull(row.uuid("introduced_by_event_id")),
            ))
        },
        matchId,
    )

    fun hasActiveIntroducedSlots(matchId: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS (
            SELECT 1 FROM match_point_lineup_entries e
            JOIN events intro ON intro.match_id = e.match_id AND intro.id = e.introduced_by_event_id
            WHERE e.match_id = ? AND intro.deleted_at IS NULL
        )""",
        Boolean::class.java, matchId,
    ) == true

    /** Insert the actor before its event; the caller binds the introduction after persisting events. */
    fun insertIncomingSlot(matchId: UUID, incoming: IntroducedPointSlot) {
        val slot = incoming.slot
        jdbc.update(
            """INSERT INTO match_point_lineup_entries (match_id, point_id, team_id, position, slot_id, slot_number)
                SELECT ?, ?, ?, COALESCE(MAX(position) + 1, 0), ?, ?
                FROM match_point_lineup_entries WHERE match_id = ? AND point_id = ? AND team_id = ?""",
            matchId, incoming.pointId, slot.teamId, slot.slotId, slot.number, matchId, incoming.pointId, slot.teamId,
        )
    }

    fun bindIntroduction(matchId: UUID, incoming: IntroducedPointSlot) {
        check(jdbc.update(
            "UPDATE match_point_lineup_entries SET introduced_by_event_id = ? WHERE match_id = ? AND slot_id = ?",
            incoming.slot.introducedByEventId, matchId, incoming.slot.slotId,
        ) == 1)
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

    /** The caller holds the match lock and updates the slot and revision in one transaction. */
    fun identifySlot(matchId: UUID, pointId: UUID, slotId: UUID, participantId: UUID?) {
        val updatedSlot = jdbc.update(
            "UPDATE match_point_lineup_entries SET resolved_participant_id = ? WHERE match_id = ? AND point_id = ? AND slot_id = ?",
            participantId, matchId, pointId, slotId,
        )
        check(updatedSlot == 1)
        val updatedLineup = jdbc.update(
            "UPDATE match_point_lineups SET revision = revision + 1 WHERE match_id = ? AND point_id = ?",
            matchId, pointId,
        )
        check(updatedLineup == 1)
    }

    private data class LineupRow(val pointId: UUID, val revision: Long, val teamId: UUID?, val entry: PointLineupEntry?)
    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)
}

data class PointLineupSlotActor(val pointId: UUID, val teamId: UUID, val resolvedParticipantId: UUID?)

data class IntroducedPointSlot(val pointId: UUID, val slot: PointSubstitutionSlot)
