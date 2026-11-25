package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import com.github.mihanizzm.ultistats.model.events.PointStatus
import com.github.mihanizzm.ultistats.service.PointLineupService
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import io.swagger.v3.oas.annotations.media.Schema
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
data class ObsInfographicsPlayer(val participantId: UUID, val name: String, val number: Int?, val photoUrl: String? = null, val temporary: Boolean = false)
data class ObsInfographicsTeam(val teamId: UUID, val name: String, val roster: List<ObsInfographicsPlayer>, val places: List<ObsInfographicsPlayer> = emptyList())
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsInfographicsPhase(
    val kind: String, val startedAt: Instant?, val pointId: UUID? = null,
    val lineupRevision: Long? = null, val lineupStartedAt: Instant? = null, val anchorKind: String? = null,
    val pageSeconds: Int = 5, val lineupPageSeconds: Int? = null,
)
data class ObsInfographicsSettings(
    val rosterPageSeconds: Int = 5, val pointPageSeconds: Int = 5, val leaderPageSeconds: Int = 5,
    val revision: Long = 0, val statsSeconds: Int = 10,
)
data class ObsInfographicsSnapshot(
    val matchId: UUID,
    val serverNow: Instant,
    val presentationRevision: String,
    val status: MatchStatus,
    val phase: ObsInfographicsPhase,
    val teams: List<ObsInfographicsTeam>,
    val settings: ObsInfographicsSettings = ObsInfographicsSettings(),
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    val leaderPresentation: ObsLeaderPresentation? = null,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(description = "Canonical team comparisons for the selected FINISHED match", nullable = true)
    val postmatch: ObsInfographicsPostmatch? = null,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(description = "Active timed final statistics for this binding", nullable = true)
    val statsPresentation: ObsPresentationReceipt? = null,
)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsAutoInfographicsSnapshot(val snapshot: ObsInfographicsSnapshot?)

@Service
class ObsInfographicsService(
    private val presentations: ObsInfographicsPresentationService,
    private val postmatchService: ObsInfographicsPostmatchService,
    private val settingsService: ObsInfographicsSettingsService,
    private val keys: ObsSourceKeyService,
    private val auto: ObsAutoSourceService,
    private val matches: SpringDataMatchRepository,
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
    private val lineups: PointLineupService,
    private val lineupRepository: PointLineupRepository,
    private val events: SpringDataEventRepository,
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

    internal fun buildAuto() = ObsAutoInfographicsSnapshot(auto.selectMatch()?.let { buildSnapshot(it, isAuto = true) })

    // Staff calls also arrive inside the preview service's original repeatable-read transaction.
    internal fun buildSnapshot(matchId: UUID, isAuto: Boolean = false): ObsInfographicsSnapshot {
        val match = matches.findByIdAndDeletedAtIsNull(matchId) ?: throw AuthProblem(404, "OBS_SOURCE_MATCH_NOT_FOUND")
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
        val now = clock.instant()
        val points = lineups.read(matchId)?.points.orEmpty()
        val next = points.firstOrNull { it.status == PointStatus.NEXT }
        val selected = next?.lineup
        val confirmedAt = selected?.let { lineupRepository.confirmedAt(matchId, next.pointId) }
        // Old rows have no confirmation history. Their canonical opening boundary is only a paging anchor.
        val openingAt = selected?.let {
            if (next.number == 1) match.effectiveStartedAt else events.findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId)
                .firstOrNull { it.id == points.getOrNull(next.number - 2)?.closingEventId }?.effectiveOccurredAt
        }
        val settings = settingsService.get()
        val rosterSeconds = settingsService.at(match.effectiveStartedAt).rosterPageSeconds
        val firstPassEnd = match.effectiveStartedAt?.plusSeconds(
            rosterSeconds.toLong() * maxOf(1, teams.maxOf { (it.roster.size + 4) / 5 }),
        )
        val anchor = confirmedAt ?: openingAt
        val lineupStart = anchor?.let { if (next?.number == 1 && firstPassEnd != null) maxOf(it, firstPassEnd) else it }
        val pointSeconds = lineupStart?.let { settingsService.at(it).pointPageSeconds }
        val active = match.status == MatchStatus.IN_PROGRESS && next != null
        val kind = when {
            !active -> "HIDDEN"
            selected != null && lineupStart != null && !now.isBefore(lineupStart) -> "LINEUP"
            next?.number == 1 -> "ROSTER"
            else -> "HIDDEN"
        }
        val phase = ObsInfographicsPhase(
            kind, when (kind) { "ROSTER" -> match.effectiveStartedAt; "LINEUP" -> lineupStart; else -> null },
            next?.pointId, selected?.revision, lineupStart,
            selected?.let { if (confirmedAt != null) "CONFIRMED" else "LEGACY_POINT_OPEN" },
            if (kind == "LINEUP") pointSeconds ?: 5 else rosterSeconds, pointSeconds,
        )
        val projected = teams.map { team ->
            val players = team.roster.associateBy { it.participantId }
            team.copy(places = selected?.teams?.firstOrNull { it.teamId == team.teamId }?.entries.orEmpty().mapNotNull { entry ->
                when (entry) {
                    is PointLineupPlayer -> players[entry.participantId]
                    is PointLineupTemporary -> ObsInfographicsPlayer(entry.slotId, "Игрок неизвестен", null, temporary = true)
                }
            })
        }
        val postmatch = if (match.status == MatchStatus.FINISHED) postmatchService.finished(matchId, teams.map { it.teamId }) else null
        val input = "${match.eventLogRevision}:${match.status}:${match.effectiveStartedAt}:$phase:$projected:${settings.revision}:$postmatch"
        val revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
        val presentation = presentations.effective(matchId, isAuto, settings.revision, now)
        return ObsInfographicsSnapshot(matchId, now, revision, match.status, phase, projected, settings,
            presentation?.leaders, postmatch, presentation?.stats)
    }
}
