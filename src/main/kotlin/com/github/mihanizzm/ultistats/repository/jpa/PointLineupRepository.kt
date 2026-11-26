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
import java.time.Instant
import java.util.UUID

@Repository
class PointLineupRepository(private val jdbc: JdbcTemplate) {
    fun confirmedAt(matchId: UUID, pointId: UUID): Instant? = jdbc.query(
        "SELECT confirmed_at FROM match_point_lineups WHERE match_id = ? AND point_id = ?",
        { row, _ -> row.getTimestamp("confirmed_at")?.toInstant() }, matchId, pointId,
    ).firstOrNull()

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

    // Only referenced actors are requested. An undone introduction still has a persisted slot.
    fun referencedSlots(matchId: UUID, slotIds: Set<UUID>): List<ReferencedPointSlot> =
        slotIds.chunked(1000).flatMap { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            jdbc.query(
                """SELECT slot_id, point_id, team_id, slot_number, resolved_participant_id
                    FROM match_point_lineup_entries
                    WHERE match_id = ? AND slot_id IN ($placeholders)""",
                { row, _ ->
                    ReferencedPointSlot(
                        requireNotNull(row.uuid("slot_id")), requireNotNull(row.uuid("point_id")),
                        requireNotNull(row.uuid("team_id")), row.getInt("slot_number"), row.uuid("resolved_participant_id"),
                    )
                },
                matchId, *ids.toTypedArray(),
            )
        }

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
            """INSERT INTO match_point_lineup_entries (match_id, point_id, team_id, position, slot_id, slot_number, resolved_participant_id)
                SELECT ?, ?, ?, COALESCE(MAX(position) + 1, 0), ?, ?, ?
                FROM match_point_lineup_entries WHERE match_id = ? AND point_id = ? AND team_id = ?""",
            matchId, incoming.pointId, slot.teamId, slot.slotId, slot.number, slot.resolvedParticipantId,
            matchId, incoming.pointId, slot.teamId,
        )
    }

    fun bindIntroduction(matchId: UUID, incoming: IntroducedPointSlot) {
        check(jdbc.update(
            "UPDATE match_point_lineup_entries SET introduced_by_event_id = ? WHERE match_id = ? AND slot_id = ?",
            incoming.slot.introducedByEventId, matchId, incoming.slot.slotId,
        ) == 1)
    }

    /** The caller holds the match lock and supplies the canonical opening boundary. */
    fun insert(matchId: UUID, point: PointBoundary, lineup: PointLineup, confirmedAt: Instant? = null) {
        jdbc.update(
            "INSERT INTO match_point_lineups (match_id, point_id, opening_event_id, revision, confirmed_at) VALUES (?, ?, ?, ?, ?)",
            matchId, point.pointId, point.openingEventId, lineup.revision, confirmedAt?.let(java.sql.Timestamp::from),
        )
        insertEntries(matchId, point.pointId, lineup.teams)
    }

    // Soft-deleted events retain their V15 foreign keys.
    fun hasEventSlotReference(matchId: UUID, slotIds: Set<UUID>): Boolean {
        if (slotIds.isEmpty()) return false
        val placeholders = slotIds.joinToString(",") { "?" }
        return jdbc.queryForObject(
            """SELECT EXISTS (SELECT 1 FROM events WHERE match_id = ?
                AND (from_slot_id IN ($placeholders) OR to_slot_id IN ($placeholders)))""",
            Boolean::class.java, matchId, *slotIds.toTypedArray(), *slotIds.toTypedArray(),
        ) == true
    }

    /** The caller holds the match lock and has checked removal of temporary actors. */
    fun replaceSelection(
        matchId: UUID, pointId: UUID, lineup: PointLineup, retainedSlotIds: Set<UUID>, removedSlotIds: Set<UUID>,
    ) {
        jdbc.update(
            "DELETE FROM match_point_lineup_entries WHERE match_id = ? AND point_id = ? AND participant_id IS NOT NULL",
            matchId, pointId,
        )
        if (removedSlotIds.isNotEmpty()) jdbc.batchUpdate(
            "DELETE FROM match_point_lineup_entries WHERE match_id = ? AND point_id = ? AND slot_id = ?",
            removedSlotIds.map { arrayOf<Any?>(matchId, pointId, it) },
        )

        val (lastPosition, firstPosition) = jdbc.queryForObject(
            """SELECT COALESCE(MAX(position), -1), COALESCE(MAX(CASE WHEN introduced_by_event_id IS NOT NULL THEN position END), -1) + 1
                FROM match_point_lineup_entries WHERE match_id = ? AND point_id = ?""",
            { row, _ -> row.getInt(1) to row.getInt(2) }, matchId, pointId,
        )!!
        if (retainedSlotIds.isNotEmpty()) {
            // Park retained initial slots above both position ranges. Their referenced keys never change.
            val offset = maxOf(lastPosition + 1, firstPosition + lineup.teams.maxOf { it.entries.size })
            jdbc.update(
                """UPDATE match_point_lineup_entries SET position = position + ?
                    WHERE match_id = ? AND point_id = ? AND introduced_by_event_id IS NULL""",
                offset, matchId, pointId,
            )
            val positions = lineup.teams.flatMap { team ->
                team.entries.mapIndexedNotNull { index, entry ->
                    (entry as? PointLineupTemporary)?.slotId?.takeIf { it in retainedSlotIds }?.let {
                        arrayOf<Any?>(firstPosition + index, matchId, pointId, it)
                    }
                }
            }
            jdbc.batchUpdate(
                "UPDATE match_point_lineup_entries SET position = ? WHERE match_id = ? AND point_id = ? AND slot_id = ?",
                positions,
            )
        }
        insertEntries(matchId, pointId, lineup.teams, firstPosition, retainedSlotIds)
        check(jdbc.update(
            "UPDATE match_point_lineups SET revision = ? WHERE match_id = ? AND point_id = ?",
            lineup.revision, matchId, pointId,
        ) == 1)
    }

    private fun insertEntries(
        matchId: UUID, pointId: UUID, teams: List<PointLineupTeam>,
        firstPosition: Int = 0, retainedSlotIds: Set<UUID> = emptySet(),
    ) {
        val rows = teams.flatMap { team ->
            team.entries.mapIndexedNotNull { index, entry ->
                val player = entry as? PointLineupPlayer
                val temporary = entry as? PointLineupTemporary
                if (temporary?.slotId in retainedSlotIds) return@mapIndexedNotNull null
                arrayOf<Any?>(matchId, pointId, team.teamId, firstPosition + index, player?.participantId,
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
        incrementRevision(matchId, pointId)
    }

    /** Replace only the initial PLAYER entry and keep its position and confirmation anchor. */
    fun replaceStarter(matchId: UUID, pointId: UUID, from: UUID, to: UUID) {
        val updated = jdbc.update(
            "UPDATE match_point_lineup_entries SET participant_id = ? WHERE match_id = ? AND point_id = ? AND participant_id = ?",
            to, matchId, pointId, from,
        )
        check(updated == 1)
        incrementRevision(matchId, pointId)
    }

    private fun incrementRevision(matchId: UUID, pointId: UUID) {
        val updatedLineup = jdbc.update(
            "UPDATE match_point_lineups SET revision = revision + 1 WHERE match_id = ? AND point_id = ?",
            matchId, pointId,
        )
        check(updatedLineup == 1)
    }

    private data class LineupRow(val pointId: UUID, val revision: Long, val teamId: UUID?, val entry: PointLineupEntry?)
    private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)
}

data class ReferencedPointSlot(
    val slotId: UUID,
    val pointId: UUID,
    val teamId: UUID,
    val number: Int,
    val resolvedParticipantId: UUID?,
)

data class PointLineupSlotActor(val pointId: UUID, val teamId: UUID, val resolvedParticipantId: UUID?)

data class IntroducedPointSlot(val pointId: UUID, val slot: PointSubstitutionSlot)
