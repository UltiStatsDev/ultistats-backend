package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsAutoScoreboardSnapshot(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val snapshot: ObsExactScoreboardSnapshot?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsAutoEventLogSnapshot(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    val snapshot: ObsExactEventLogSnapshot?,
)

@Service
class ObsAutoSourceService(
    private val keys: ObsSourceKeyService,
    private val matches: SpringDataMatchRepository,
    private val scoreboard: ObsScoreboardService,
    private val eventLog: ObsExactEventLogService,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun scoreboard(authorization: List<String>): ObsAutoScoreboardSnapshot {
        keys.authorizeSource(null, ObsSourceKeyScope.AUTO_SCOREBOARD, authorization)
        return buildScoreboard()
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun eventLog(authorization: List<String>): ObsAutoEventLogSnapshot {
        keys.authorizeSource(null, ObsSourceKeyScope.AUTO_EVENT_LOG, authorization)
        return buildEventLog()
    }

    // Called only within the viewer or staff caller's repeatable-read transaction.
    internal fun buildScoreboard() = ObsAutoScoreboardSnapshot(selectMatch()?.let(scoreboard::exactSnapshot))
    internal fun buildEventLog() = ObsAutoEventLogSnapshot(selectMatch()?.let(eventLog::buildSnapshot))

    // Compare canonical UUID text: UUID.compareTo uses signed halves, not canonical UUID order.
    // effectiveStartedAt validates original precision against the physical stored timestamp.
    private val startOrder = compareByDescending<Match> { it.effectiveStartedAt }.thenBy { it.id.toString() }

    internal fun selectMatch(): UUID? = select(matches.findAllByDeletedAtIsNull())?.id

    private fun select(candidates: List<Match>): Match? = candidates
        .filter { it.deletedAt == null && it.effectiveStartedAt != null }
        .minWithOrNull(compareBy<Match> { it.endedAt != null }.then(startOrder))

    internal data class PresentationSelection(val matchId: UUID, val revision: String)

    internal fun presentationSelection(): PresentationSelection? {
        val candidates = matches.findAllByStartedAtIsNotNull()
        val selected = select(candidates) ?: return null
        // A later contender can start and disappear between polls. Retain its lifecycle in the AUTO token.
        val history = candidates.filter { selected.endedAt != null || startOrder.compare(it, selected) <= 0 }
            .sortedBy { it.id.toString() }
            .joinToString("|") { "${it.id}:${it.effectiveStartedAt}:${it.effectiveEndedAt}:${it.deletedAt}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(history.toByteArray(Charsets.UTF_8))
        return PresentationSelection(selected.id, HexFormat.of().formatHex(digest))
    }
}
