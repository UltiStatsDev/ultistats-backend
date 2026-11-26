package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.service.MatchFinishApplied
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class ObsPresentationBinding { EXACT, AUTO }
enum class ObsPresentationKind { LEADERS, STATS, ROSTER, POINT }
enum class ObsPresentationOrigin { MANUAL, AUTO }

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
    @field:Schema(defaultValue = "LEADERS", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val kind: ObsPresentationKind = ObsPresentationKind.LEADERS,
)

data class ObsPhaseHideRequest(
    val binding: ObsPresentationBinding,
    val expectedMatchId: UUID,
    @param:JsonProperty(required = true)
    @field:Schema(types = ["string", "null"], format = "uuid")
    val expectedGeneration: UUID?,
    val phaseToken: String,
    val operationId: UUID,
)

data class ObsActivePhase(
    val kind: ObsPresentationKind, val startedAt: Instant, val phaseToken: String,
    val origin: ObsPresentationOrigin = ObsPresentationOrigin.AUTO,
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
    val canShowStats: Boolean,
    val serverNow: Instant,
    @field:Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    val activePresentation: ObsPresentationReceipt?,
    val binding: ObsPresentationBinding,
    val matchId: UUID,
    val availability: Map<ObsPresentationKind, Boolean>,
    val activePhase: ObsActivePhase?,
)

data class ObsPresentationReceipt(
    val generation: UUID, val matchId: UUID, val startedAt: Instant, val expiresAt: Instant, val pageSeconds: Int,
    val kind: ObsPresentationKind, val origin: ObsPresentationOrigin,
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
    private val baseService: ObsInfographicsBaseService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    private data class Entry(
        val generation: UUID, val matchId: UUID, val kind: ObsPresentationKind, val origin: ObsPresentationOrigin,
        val startedAt: Instant, val expiresAt: Instant?, val pageSeconds: Int?, val phaseToken: String? = null,
    ) {
        fun receipt() = expiresAt?.let {
            ObsPresentationReceipt(generation, matchId, startedAt, it, checkNotNull(pageSeconds), kind, origin)
        }
    }

    private data class Command(
        val entry: Entry, val binding: String, val ordinal: Long, val prior: UUID?,
        val input: String, val settingsRevision: Long, val hidden: Boolean, val teamsJson: String,
    ) {
        fun active(matchId: UUID, revision: Long, now: Instant) = entry.receipt()?.takeIf {
            !hidden && it.matchId == matchId && now >= it.startedAt && now < it.expiresAt &&
                (it.kind != ObsPresentationKind.LEADERS || settingsRevision == revision)
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun exactLeaders(matchId: UUID) = control(matchId, null)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun autoLeaders(): ObsAutoLeadersSnapshot {
        val selection = auto.presentationSelection()
        return ObsAutoLeadersSnapshot(selection?.let { control(it.matchId, it.revision) })
    }

    private fun control(matchId: UUID, selectionRevision: String?): ObsLeadersSnapshot {
        val now = clock.instant()
        val base = baseService.read(matchId, now)
        val ratings = leaders.exact(matchId)
        val revision = base.settings.revision
        val input = input(ratings, selectionRevision)
        val isAuto = selectionRevision != null
        val latest = latest(binding(matchId, isAuto))
        val active = latest?.takeIf { it.input == input }?.active(matchId, revision, now)
        val phase = currentPhase(base, selectionRevision)?.takeUnless { active != null || latest?.entry?.phaseToken == it.phaseToken }
        val availability = ObsPresentationKind.entries.associateWith { pages(it, ratings, base) > 0 }
        return ratings.copy(presentation = ObsPresentationControl(input, revision, latest?.entry?.generation,
            active?.generation, availability.getValue(ObsPresentationKind.LEADERS), availability.getValue(ObsPresentationKind.STATS),
            now, active, if (isAuto) ObsPresentationBinding.AUTO else ObsPresentationBinding.EXACT, matchId, availability, phase))
    }

    internal data class Active(
        val leaders: ObsLeaderPresentation? = null, val stats: ObsPresentationReceipt? = null,
        val players: ObsPresentationReceipt? = null, val suppressedPhase: String? = null,
    )

    // Called inside the original source/preview repeatable-read snapshot, after source authorization.
    internal fun effective(base: ObsInfographicsBase, isAuto: Boolean, now: Instant): Active? {
        val matchId = base.match.id
        val current = latest(binding(matchId, isAuto)) ?: return null
        val phaseToken = current.entry.phaseToken
        val receipt = current.active(matchId, base.settings.revision, now)
        if (phaseToken == null && receipt == null) return null
        val selection = if (isAuto) auto.presentationSelection()?.takeIf { it.matchId == matchId } ?: return null else null
        if (phaseToken != null) {
            return if (currentPhase(base, selection?.revision)?.phaseToken == phaseToken) Active(suppressedPhase = base.phase.kind) else null
        }
        if (receipt == null || current.input != input(leaders.exact(matchId), selection?.revision)) return null
        return when (receipt.kind) {
            ObsPresentationKind.STATS -> Active(stats = receipt)
            ObsPresentationKind.ROSTER, ObsPresentationKind.POINT -> Active(players = receipt)
            ObsPresentationKind.LEADERS -> Active(leaders = ObsLeaderPresentation(
                receipt.generation, receipt.startedAt, receipt.expiresAt, receipt.pageSeconds,
                mapper.readValue(current.teamsJson, object : TypeReference<List<ObsTeamLeaders>>() {}),
            ))
        }
    }

    private fun currentPhase(base: ObsInfographicsBase, selectionRevision: String?): ObsActivePhase? {
        val phase = base.phase
        val kind = when (phase.kind) {
            "ROSTER" -> ObsPresentationKind.ROSTER
            "LINEUP" -> ObsPresentationKind.POINT
            else -> return null
        }
        val start = phase.startedAt ?: return null
        // Confirmation of the next lineup during the first roster pass keeps the roster epoch.
        val token = obsInfographicsDigest("${base.match.id}|${selectionRevision ?: "EXACT"}|$kind|$start|${phase.pointId}")
        return ObsActivePhase(kind, start, token)
    }

    private fun pages(kind: ObsPresentationKind, ratings: ObsLeadersSnapshot, base: ObsInfographicsBase): Long =
        when (kind) {
            ObsPresentationKind.STATS -> if (ratings.status == MatchStatus.FINISHED) 1 else 0
            ObsPresentationKind.LEADERS -> if (ratings.status != MatchStatus.PLANNED) slots(ratings.teams) else 0
            ObsPresentationKind.ROSTER, ObsPresentationKind.POINT -> base.playerPages(kind)
        }

    // The match writer invokes this synchronously after validation and flush, under the match lock.
    // The original finish, both generation writes and their deadlines commit or roll back together.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun autoShowStats(signal: MatchFinishApplied) {
        val matchId = signal.matchId
        val exact = binding(matchId, false)
        val automatic = binding(matchId, true)
        lockBinding(exact)
        lockBinding(automatic)
        val selection = auto.presentationSelection()?.takeIf { it.matchId == matchId }
        val ratings = leaders.exact(matchId)
        check(ratings.status == MatchStatus.FINISHED)
        val policy = settings.get()
        val start = launchTime()
        for ((binding, selectionRevision) in listOf(exact to null, automatic to selection?.revision)) {
            if (binding == automatic && selectionRevision == null) continue
            val entry = finiteEntry(matchId, ObsPresentationKind.STATS, ObsPresentationOrigin.AUTO, start, policy.statsSeconds, 1)
            check(append(entry, UUID.randomUUID(), binding, latest(binding), input(ratings, selectionRevision), policy.revision))
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun show(request: ObsPresentationRequest): ObsPresentationReceipt {
        if (request.expectedSettingsRevision == null || request.expectedSettingsRevision < 0 || !request.expectedInputRevision.matches(revisionPattern)) {
            throw AuthProblem(400, "OBS_PRESENTATION_INVALID_INPUT")
        }
        val isAuto = request.binding == ObsPresentationBinding.AUTO
        val binding = binding(request.expectedMatchId, isAuto)
        retry(request, binding)?.let { return it }
        // Acquire before AUTO or statistics populate the JPA persistence context.
        matches.findByIdForUpdate(request.expectedMatchId) ?: conflict("OBS_PRESENTATION_INPUT_STALE")
        lockBinding(binding)
        retry(request, binding)?.let { return it }
        val previous = latest(binding)
        if (previous?.entry?.generation != request.expectedGeneration) conflict("OBS_PRESENTATION_GENERATION_STALE")

        val selectionRevision = selectionRevision(request.expectedMatchId, isAuto)
        val ratings = leaders.exact(request.expectedMatchId)
        val revision = input(ratings, selectionRevision)
        val base = baseService.read(request.expectedMatchId, clock.instant())
        val currentSettings = base.settings
        if (revision != request.expectedInputRevision || currentSettings.revision != request.expectedSettingsRevision) {
            conflict("OBS_PRESENTATION_INPUT_STALE")
        }

        val slotCount = pages(request.kind, ratings, base)
        if (slotCount == 0L) conflict("OBS_PRESENTATION_NO_${request.kind.name}")
        val seconds = when (request.kind) {
            ObsPresentationKind.LEADERS -> currentSettings.leaderPageSeconds
            ObsPresentationKind.STATS -> currentSettings.statsSeconds
            ObsPresentationKind.ROSTER -> currentSettings.rosterPageSeconds
            ObsPresentationKind.POINT -> currentSettings.pointPageSeconds
        }
        val entry = finiteEntry(ratings.matchId, request.kind, ObsPresentationOrigin.MANUAL, launchTime(), seconds, slotCount)
        val teamsJson = if (request.kind == ObsPresentationKind.LEADERS) mapper.writeValueAsString(ratings.teams) else "[]"
        if (!append(entry, request.operationId, binding, previous, revision, currentSettings.revision, teamsJson)) {
            // Global operation IDs can race across bindings even though each binding is serialized.
            return retry(request, binding) ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        }
        return checkNotNull(entry.receipt())
    }

    private fun launchTime() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private fun finiteEntry(
        matchId: UUID, kind: ObsPresentationKind, origin: ObsPresentationOrigin, start: Instant, seconds: Int, slots: Long,
    ) = Entry(UUID.randomUUID(), matchId, kind, origin, start, start.plusSeconds(seconds * slots), seconds)

    private fun append(
        entry: Entry, operationId: UUID, binding: String, previous: Command?,
        revision: String, settingsRevision: Long, teamsJson: String = "[]",
    ) = jdbc.update(
        """INSERT INTO obs_infographics_presentations
           (generation, operation_id, binding, ordinal, prior_generation, match_id, input_revision, settings_revision,
            started_at, expires_at, page_seconds, teams_json, kind, origin, phase_token, hidden)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING""",
        entry.generation, operationId, binding, (previous?.ordinal ?: 0) + 1, previous?.entry?.generation,
        entry.matchId, revision, settingsRevision, Timestamp.from(entry.startedAt), entry.expiresAt?.let(Timestamp::from),
        entry.pageSeconds, teamsJson, entry.kind.name, entry.origin.name, entry.phaseToken, entry.phaseToken != null,
    ) == 1

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun hide(generation: UUID) {
        val command = find("generation = ?", generation)?.takeIf { it.entry.phaseToken == null } ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        lockBinding(command.binding)
        val current = latest(command.binding)?.takeIf { it.entry.generation == generation }
            ?: conflict("OBS_PRESENTATION_GENERATION_STALE")
        if (!current.hidden) jdbc.update("UPDATE obs_infographics_presentations SET hidden = TRUE WHERE generation = ?", generation)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun hidePhase(request: ObsPhaseHideRequest) {
        if (!phaseTokenPattern.matches(request.phaseToken)) throw AuthProblem(400, "OBS_PRESENTATION_INVALID_INPUT")
        val isAuto = request.binding == ObsPresentationBinding.AUTO
        val binding = binding(request.expectedMatchId, isAuto)
        if (retriedPhaseHide(request, binding)) return
        matches.findByIdForUpdate(request.expectedMatchId) ?: conflict("OBS_PRESENTATION_INPUT_STALE")
        lockBinding(binding)
        if (retriedPhaseHide(request, binding)) return
        val previous = latest(binding)
        if (previous?.entry?.generation != request.expectedGeneration) conflict("OBS_PRESENTATION_GENERATION_STALE")

        val selectionRevision = selectionRevision(request.expectedMatchId, isAuto)
        val now = launchTime()
        val base = baseService.read(request.expectedMatchId, now)
        val phase = currentPhase(base, selectionRevision)?.takeIf { it.phaseToken == request.phaseToken }
            ?: conflict("OBS_PRESENTATION_PHASE_STALE")
        val revision = input(leaders.exact(request.expectedMatchId), selectionRevision)
        if (previous?.entry?.phaseToken == phase.phaseToken ||
            previous?.takeIf { it.input == revision }?.active(request.expectedMatchId, base.settings.revision, now) != null) {
            conflict("OBS_PRESENTATION_PHASE_STALE")
        }
        val entry = Entry(UUID.randomUUID(), request.expectedMatchId, phase.kind, ObsPresentationOrigin.MANUAL,
            now, null, null, phase.phaseToken)
        if (!append(entry, request.operationId, binding, previous, revision, base.settings.revision) &&
            !retriedPhaseHide(request, binding)) conflict("OBS_PRESENTATION_GENERATION_STALE")
    }

    private fun retriedPhaseHide(request: ObsPhaseHideRequest, binding: String): Boolean =
        find("operation_id = ?", request.operationId)?.let {
            if (it.binding != binding || it.entry.matchId != request.expectedMatchId ||
                it.prior != request.expectedGeneration || it.entry.phaseToken != request.phaseToken) {
                conflict("OBS_PRESENTATION_OPERATION_CONFLICT")
            }
            true
        } ?: false

    private fun retry(request: ObsPresentationRequest, binding: String): ObsPresentationReceipt? =
        find("operation_id = ?", request.operationId)?.let {
            if (it.binding != binding || it.entry.matchId != request.expectedMatchId || it.prior != request.expectedGeneration ||
                it.input != request.expectedInputRevision || it.settingsRevision != request.expectedSettingsRevision ||
                it.entry.kind != request.kind || it.entry.origin != ObsPresentationOrigin.MANUAL || it.entry.phaseToken != null) {
                conflict("OBS_PRESENTATION_OPERATION_CONFLICT")
            }
            checkNotNull(it.entry.receipt())
        }

    private fun selectionRevision(matchId: UUID, isAuto: Boolean): String? =
        if (isAuto) auto.presentationSelection()?.takeIf { it.matchId == matchId }?.revision
            ?: conflict("OBS_PRESENTATION_INPUT_STALE") else null

    private fun input(ratings: ObsLeadersSnapshot, selection: String?) =
        if (selection == null) ratings.inputRevision else "${ratings.inputRevision}.$selection"

    private fun slots(teams: List<ObsTeamLeaders>) =
        teams.maxOfOrNull { it.attack.size.toLong() }.orZero() + teams.maxOfOrNull { it.defense.size.toLong() }.orZero()

    private fun Long?.orZero() = this ?: 0L

    private fun binding(matchId: UUID, isAuto: Boolean) = if (isAuto) "AUTO" else matchId.toString()

    private fun lockBinding(binding: String) {
        jdbc.update("INSERT INTO obs_infographics_presentation_bindings (binding) VALUES (?) ON CONFLICT DO NOTHING", binding)
        jdbc.queryForObject("SELECT binding FROM obs_infographics_presentation_bindings WHERE binding = ? FOR UPDATE", String::class.java, binding)
    }

    private fun latest(binding: String) = find("binding = ? ORDER BY ordinal DESC LIMIT 1", binding)

    private fun find(predicate: String, value: Any) = jdbc.query(
        "SELECT * FROM obs_infographics_presentations WHERE $predicate",
        { rs, _ ->
            val entry = Entry(rs.getObject("generation", UUID::class.java), rs.getObject("match_id", UUID::class.java),
                ObsPresentationKind.valueOf(rs.getString("kind")), ObsPresentationOrigin.valueOf(rs.getString("origin")),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("expires_at")?.toInstant(),
                rs.getObject("page_seconds", Int::class.javaObjectType), rs.getString("phase_token"))
            Command(entry, rs.getString("binding"), rs.getLong("ordinal"), rs.getObject("prior_generation", UUID::class.java),
                rs.getString("input_revision"), rs.getLong("settings_revision"), rs.getBoolean("hidden"), rs.getString("teams_json"))
        }, value,
    ).singleOrNull()

    private fun conflict(code: String): Nothing = throw AuthProblem(409, code)

    private companion object {
        val phaseTokenPattern = Regex("[0-9a-f]{64}")
        val revisionPattern = Regex("[0-9a-f]{64}(\\.[0-9a-f]{64})?")
    }
}
