package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class ObsPresentationBinding { EXACT, AUTO }

data class ObsPresentationRequest(
    val binding: ObsPresentationBinding,
    val expectedMatchId: UUID,
    val expectedInputRevision: String,
    @param:JsonProperty(required = true)
    @field:Schema(type = "integer", format = "int64", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedSettingsRevision: Long?,
    @param:JsonProperty(required = true)
    @field:Schema(types = ["string", "null"], format = "uuid")
    val expectedGeneration: UUID?,
    val operationId: UUID,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsPresentationControl(
    val expectedInputRevision: String,
    val expectedSettingsRevision: Long,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedGeneration: UUID?,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val activeGeneration: UUID?,
    val canShow: Boolean,
)

data class ObsPresentationReceipt(
    val generation: UUID, val matchId: UUID, val startedAt: Instant, val expiresAt: Instant, val pageSeconds: Int,
)

data class ObsLeaderPresentation(
    val generation: UUID, val startedAt: Instant, val expiresAt: Instant, val pageSeconds: Int,
    val teams: List<ObsTeamLeaders>,
)

@Service
class ObsInfographicsPresentationService(
    private val jdbc: JdbcTemplate,
    private val matches: SpringDataMatchRepository,
    private val auto: ObsAutoSourceService,
    private val leaders: ObsInfographicsLeadersService,
    private val settings: ObsInfographicsSettingsService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private data class Command(
        val receipt: ObsPresentationReceipt, val binding: String, val ordinal: Long, val prior: UUID?,
        val input: String, val settingsRevision: Long, val hidden: Boolean, val teamsJson: String,
    ) {
        fun applies(matchId: UUID, revision: Long, now: Instant) = !hidden && receipt.matchId == matchId &&
            settingsRevision == revision && now >= receipt.startedAt && now < receipt.expiresAt
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun exactLeaders(matchId: UUID) = control(leaders.exact(matchId), null)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun autoLeaders(): ObsAutoLeadersSnapshot {
        val selection = auto.presentationSelection()
        return ObsAutoLeadersSnapshot(selection?.let { control(leaders.exact(it.matchId), it.revision) })
    }

    private fun control(ratings: ObsLeadersSnapshot, selectionRevision: String?): ObsLeadersSnapshot {
        val revision = settings.get().revision
        val input = input(ratings, selectionRevision)
        val latest = latest(if (selectionRevision == null) ratings.matchId.toString() else "AUTO")
        val active = latest?.takeIf { it.applies(ratings.matchId, revision, clock.instant()) && it.input == input }
        return ratings.copy(presentation = ObsPresentationControl(input, revision, latest?.receipt?.generation,
            active?.receipt?.generation, ratings.status != MatchStatus.PLANNED && slots(ratings.teams) > 0))
    }

    // Called inside the original source/preview repeatable-read snapshot, after source authorization.
    internal fun effective(matchId: UUID, isAuto: Boolean, revision: Long, now: Instant): ObsLeaderPresentation? {
        val current = latest(if (isAuto) "AUTO" else matchId.toString()) ?: return null
        if (!current.applies(matchId, revision, now)) return null
        val selection = if (isAuto) auto.presentationSelection()?.takeIf { it.matchId == matchId } ?: return null else null
        if (current.input != input(leaders.exact(matchId), selection?.revision)) return null
        val receipt = current.receipt
        return ObsLeaderPresentation(receipt.generation, receipt.startedAt, receipt.expiresAt, receipt.pageSeconds,
            mapper.readValue(current.teamsJson, object : TypeReference<List<ObsTeamLeaders>>() {}))
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun show(request: ObsPresentationRequest): ObsPresentationReceipt {
        if (request.expectedSettingsRevision == null || request.expectedSettingsRevision < 0 || !request.expectedInputRevision.matches(revisionPattern)) {
            throw AuthProblem(400, "OBS_PRESENTATION_INVALID_INPUT")
        }
        val isAuto = request.binding == ObsPresentationBinding.AUTO
        val binding = if (isAuto) "AUTO" else request.expectedMatchId.toString()
        retry(request, binding)?.let { return it }
        // Acquire before AUTO or statistics populate the JPA persistence context.
        matches.findByIdForUpdate(request.expectedMatchId) ?: conflict("OBS_PRESENTATION_INPUT_STALE")
        lockLatest(binding)
        retry(request, binding)?.let { return it }
        val previous = latest(binding)
        if (previous?.receipt?.generation != request.expectedGeneration) conflict("OBS_PRESENTATION_GENERATION_STALE")

        val selection = if (isAuto) auto.presentationSelection()?.takeIf { it.matchId == request.expectedMatchId }
            ?: conflict("OBS_PRESENTATION_INPUT_STALE") else null
        val ratings = leaders.exact(request.expectedMatchId)
        val revision = input(ratings, selection?.revision)
        val currentSettings = settings.get()
        if (revision != request.expectedInputRevision || currentSettings.revision != request.expectedSettingsRevision) {
            conflict("OBS_PRESENTATION_INPUT_STALE")
        }
        val slotCount = slots(ratings.teams)
        if (ratings.status == MatchStatus.PLANNED || slotCount == 0L) conflict("OBS_PRESENTATION_NO_LEADERS")

        val start = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val seconds = currentSettings.leaderPageSeconds
        val receipt = ObsPresentationReceipt(UUID.randomUUID(), ratings.matchId, start, start.plusSeconds(slotCount * seconds), seconds)
        // Empty bindings have no row to lock. The unique ordinal arbitrates concurrent first shows.
        val inserted = jdbc.update(
            """INSERT INTO obs_infographics_presentations
               (generation, operation_id, binding, ordinal, prior_generation, match_id, input_revision, settings_revision,
                started_at, expires_at, page_seconds, teams_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
            receipt.generation, request.operationId, binding, (previous?.ordinal ?: 0) + 1, request.expectedGeneration,
            receipt.matchId, revision, currentSettings.revision, Timestamp.from(start), Timestamp.from(receipt.expiresAt),
            seconds, mapper.writeValueAsString(ratings.teams),
        )
        if (inserted == 0) return retry(request, binding) ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        return receipt
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun hide(generation: UUID) {
        val command = find("generation = ?", generation) ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        lockLatest(command.binding)
        val current = latest(command.binding)?.takeIf { it.receipt.generation == generation }
            ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        if (!current.hidden) jdbc.update("UPDATE obs_infographics_presentations SET hidden = TRUE WHERE generation = ?", generation)
    }

    private fun retry(request: ObsPresentationRequest, binding: String): ObsPresentationReceipt? =
        find("operation_id = ?", request.operationId)?.let {
            if (it.binding != binding || it.receipt.matchId != request.expectedMatchId || it.prior != request.expectedGeneration ||
                it.input != request.expectedInputRevision || it.settingsRevision != request.expectedSettingsRevision) {
                conflict("OBS_PRESENTATION_OPERATION_CONFLICT")
            }
            it.receipt
        }

    private fun input(ratings: ObsLeadersSnapshot, selection: String?) =
        if (selection == null) ratings.inputRevision else "${ratings.inputRevision}.$selection"

    private fun slots(teams: List<ObsTeamLeaders>) =
        teams.maxOfOrNull { it.attack.size.toLong() }.orZero() + teams.maxOfOrNull { it.defense.size.toLong() }.orZero()

    private fun Long?.orZero() = this ?: 0L

    private fun lockLatest(binding: String) {
        jdbc.query("SELECT generation FROM obs_infographics_presentations WHERE binding = ? ORDER BY ordinal DESC LIMIT 1 FOR UPDATE",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, binding)
        // READ_COMMITTED callers must re-read latest after waiting on the row.
    }

    private fun latest(binding: String) = find("binding = ? ORDER BY ordinal DESC LIMIT 1", binding)

    private fun find(predicate: String, value: Any) = jdbc.query(
        "SELECT * FROM obs_infographics_presentations WHERE $predicate",
        { rs, _ ->
            val receipt = ObsPresentationReceipt(rs.getObject("generation", UUID::class.java),
                rs.getObject("match_id", UUID::class.java), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("page_seconds"))
            Command(receipt, rs.getString("binding"), rs.getLong("ordinal"), rs.getObject("prior_generation", UUID::class.java),
                rs.getString("input_revision"), rs.getLong("settings_revision"), rs.getBoolean("hidden"), rs.getString("teams_json"))
        }, value,
    ).singleOrNull()

    private fun conflict(code: String): Nothing = throw AuthProblem(409, code)

    private companion object {
        val revisionPattern = Regex("[0-9a-f]{64}(\\.[0-9a-f]{64})?")
    }
}
