package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.dto.request.PointStarterCorrectionRequest
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionBoundary
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionBoundaryKind.OUTGOING_SUBSTITUTION
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionBoundaryKind.POINT_CLOSE
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionEvent
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionPreview
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionSide.FROM
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionSide.TO
import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.PointBoundary
import java.util.UUID

internal data class PointStarterCorrection(
    val lineup: PointLineup,
    val preview: PointStarterCorrectionPreview,
    val changedRows: Map<UUID, EventEntity>,
) {
    companion object {
        fun propose(
            point: PointBoundary,
            rows: List<EventEntity>,
            lineup: PointLineup,
            request: PointStarterCorrectionRequest,
            rosterTeams: Map<UUID, UUID>,
        ): PointStarterCorrection {
            val from = request.fromParticipantId
            val to = request.toParticipantId
            val teamId = rosterTeams[from]
            if (teamId == null || rosterTeams[to] != teamId) {
                conflict("Both participants must be known roster players on the same match team")
            }
            val team = lineup.teams.firstOrNull { it.teamId == teamId }
                ?: conflict("The starting player does not belong to this point lineup")
            val entryIndex = team.entries.indexOf(PointLineupPlayer(from))
            if (entryIndex < 0) conflict("The original player must occupy an initial PLAYER entry")
            if (team.entries.any { it == PointLineupPlayer(to) || it is PointLineupTemporary && it.resolvedParticipantId == to }) {
                conflict("The replacement player already occupies a starting position in this point")
            }

            val segment = rows.dropWhile { it.id != point.pullEventId }
            val outgoing = segment.firstOrNull { it.eventType == EventType.PLAYER_SUBSTITUTION && it.fromParticipantId == from }
            val closingId = requireNotNull(point.closingEventId)
            val boundary = if (outgoing == null) PointStarterCorrectionBoundary(POINT_CLOSE, closingId)
                else PointStarterCorrectionBoundary(OUTGOING_SUBSTITUTION, outgoing.id)

            if (outgoing != null && segment.first().fromParticipantId == from &&
                segment.dropWhile { it.id != outgoing.id }.any { it.eventType == EventType.BRICK && it.fromParticipantId == from }) {
                conflict("A BRICK after the outgoing substitution would no longer refer to its original PULL")
            }

            val changes = mutableListOf<PointStarterCorrectionEvent>()
            val changedRows = buildMap {
                for (row in segment) {
                    val touchesFrom = row.fromParticipantId == from
                    val touchesTo = row.id != outgoing?.id && row.toParticipantId == from
                    if (touchesFrom || touchesTo) {
                        if ((touchesFrom && row.toParticipantId == to) || (touchesTo && row.fromParticipantId == to)) {
                            conflict("The correction would put the replacement player on both sides of ${row.eventType}")
                        }
                        val sides = listOfNotNull(FROM.takeIf { touchesFrom }, TO.takeIf { touchesTo })
                        changes += PointStarterCorrectionEvent(row.id, row.eventType, sides)
                        put(row.id, row.copy(
                            fromParticipantId = if (touchesFrom) to else row.fromParticipantId,
                            toParticipantId = if (touchesTo) to else row.toParticipantId,
                            creationFingerprint = null,
                        ))
                    }
                    if (row.id == boundary.eventId) break
                }
            }
            val updated = lineup.copy(revision = lineup.revision + 1, teams = lineup.teams.map {
                if (it.teamId == teamId) it.copy(entries = it.entries.toMutableList().apply { set(entryIndex, PointLineupPlayer(to)) })
                else it
            })
            val preview = PointStarterCorrectionPreview(point.pointId, teamId, entryIndex, from, to, boundary, changes)
            return PointStarterCorrection(updated, preview, changedRows)
        }

        private fun conflict(detail: String): Nothing =
            throw PointLineupRejectedException(PointLineupProblemCode.POINT_LINEUP_CONFLICT, detail)
    }
}
