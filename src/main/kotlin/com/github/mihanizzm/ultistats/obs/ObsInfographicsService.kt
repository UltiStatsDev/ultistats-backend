package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat
import java.time.Clock
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsInfographicsPlayer(val participantId: UUID, val name: String, val number: Int?, val photoUrl: String? = null)
data class ObsInfographicsTeam(val teamId: UUID, val name: String, val roster: List<ObsInfographicsPlayer>)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsInfographicsPhase(val kind: String, val startedAt: Instant?)
data class ObsInfographicsSettings(val rosterPageSeconds: Int = 5, val pointPageSeconds: Int = 5, val leaderPageSeconds: Int = 5)
data class ObsInfographicsSnapshot(
    val matchId: UUID,
    val serverNow: Instant,
    val presentationRevision: String,
    val status: MatchStatus,
    val phase: ObsInfographicsPhase,
    val teams: List<ObsInfographicsTeam>,
    val settings: ObsInfographicsSettings = ObsInfographicsSettings(),
)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsAutoInfographicsSnapshot(val snapshot: ObsInfographicsSnapshot?)

@Service
class ObsInfographicsService(
    private val keys: ObsSourceKeyService,
    private val auto: ObsAutoSourceService,
    private val matches: SpringDataMatchRepository,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    // The first key lookup and all selected-match reads share the original snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun exact(matchId: UUID, authorization: List<String>): ObsInfographicsSnapshot {
        keys.authorizeSource(matchId, ObsSourceKeyScope.EXACT_INFOGRAPHICS, authorization)
        return buildSnapshot(matchId)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun auto(authorization: List<String>): ObsAutoInfographicsSnapshot {
        keys.authorizeSource(null, ObsSourceKeyScope.AUTO_INFOGRAPHICS, authorization)
        return buildAuto()
    }

    internal fun buildAuto() = ObsAutoInfographicsSnapshot(auto.selectMatch()?.let(::buildSnapshot))

    // Staff calls also arrive inside the preview service's original repeatable-read transaction.
    internal fun buildSnapshot(matchId: UUID): ObsInfographicsSnapshot {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
        val pulled = jdbc.queryForObject(
            "SELECT COUNT(*) FROM events WHERE match_id = ? AND event_type = 'PULL' AND deleted_at IS NULL",
            Long::class.java, matchId,
        )!! > 0
        val roster = jdbc.query(
            """
                SELECT participant_id, team_id, first_name, last_name, number FROM match_participants
                WHERE match_id = ? AND kind = 'PLAYER' ORDER BY participant_id
            """.trimIndent(),
            { rs, _ -> rs.getObject("team_id", UUID::class.java) to ObsInfographicsPlayer(
                rs.getObject("participant_id", UUID::class.java),
                "${rs.getString("first_name")} ${rs.getString("last_name")}",
                rs.getObject("number", Int::class.javaObjectType),
            ) }, matchId,
        ).groupBy({ it.first }, { it.second })
        val teams = jdbc.query(
            "SELECT team_id, team_name FROM match_teams WHERE match_id = ? ORDER BY position",
            { rs, _ -> rs.getObject("team_id", UUID::class.java).let { ObsInfographicsTeam(it, rs.getString("team_name"), roster[it].orEmpty()) } },
            matchId,
        )
        val visible = match.status == MatchStatus.IN_PROGRESS && !pulled
        val phase = ObsInfographicsPhase(if (visible) "ROSTER" else "HIDDEN", if (visible) match.effectiveStartedAt else null)
        val input = "${match.eventLogRevision}:${match.status}:${match.effectiveStartedAt}:$pulled:$teams"
        val revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
        return ObsInfographicsSnapshot(matchId, clock.instant(), revision, match.status, phase, teams)
    }
}
