package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.facade.StatisticsFacade
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

data class ObsLeadersSnapshot(
    val matchId: UUID, val status: MatchStatus, val inputRevision: String, val teams: List<ObsTeamLeaders>,
    val presentation: ObsPresentationControl? = null,
)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsAutoLeadersSnapshot(val snapshot: ObsLeadersSnapshot?)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class ObsInfographicsLeadersService(
    private val statistics: StatisticsFacade,
    private val mapper: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val matches: SpringDataMatchRepository,
) {
    fun exact(matchId: UUID): ObsLeadersSnapshot {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        val stats = statistics.get(matchId) ?: throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        val teams = stats.teams.map { ObsInfographicsRatings.team(it.teamId, it.teamName, it.participants) }
        // Include ALL selected roster identity/counter facts, including non-winning and unknown entries.
        // A name/identification correction or undo changes this input; polling time never does.
        val inputs = stats.teams.map { team ->
            listOf(team.teamId, team.teamName, team.participants.sortedBy { it.participantId.toString() }.map {
                listOf(it.participantId, it.kind, it.unknownSlot, it.firstName, it.lastName, it.displayName, it.number, it.attack, it.defense)
            })
        }
        val identities = jdbc.query(
            """SELECT point_id, team_id, position, participant_id, slot_id, resolved_participant_id
               FROM match_point_lineup_entries WHERE match_id = ? ORDER BY point_id, team_id, position""",
            { rs, _ -> (1..6).map { rs.getObject(it)?.toString() } }, matchId,
        )
        // Identify then unidentify can restore both names and counters, but a lineup revision never rewinds.
        val lineupRevisions = jdbc.query(
            "SELECT point_id, revision FROM match_point_lineups WHERE match_id = ? ORDER BY point_id",
            { rs, _ -> rs.getObject(1).toString() to rs.getLong(2) }, matchId,
        )
        val bytes = mapper.writeValueAsBytes(listOf(matchId, match.status, match.effectiveStartedAt, match.effectiveEndedAt,
            match.eventLogRevision, lineupRevisions, identities, inputs))
        val revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return ObsLeadersSnapshot(matchId, match.status, revision, teams)
    }
}
