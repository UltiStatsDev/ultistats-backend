package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.github.mihanizzm.ultistats.model.MatchStatus
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

/** Presentation overrides only; empty maps and original order are the reset profile. */
data class ObsScoreboardProfile(
    @field:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    @field:Size(max = 2) val shortNames: Map<UUID, String> = emptyMap(),
    @field:JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    @field:Size(max = 2) val teamColors: Map<UUID, String> = emptyMap(),
    @field:JsonSetter(nulls = Nulls.FAIL) val swapped: Boolean = false,
)

data class ObsScoreboardTeam(val teamId: UUID, val teamName: String, val photoUrl: String?, val score: Int)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsScoreboardSnapshot(
    val matchId: UUID,
    val teams: List<ObsScoreboardTeam>,
    val status: MatchStatus,
    @field:Schema(types = ["string", "null"], format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    val startedAt: Instant?,
    @field:Schema(types = ["string", "null"], format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    val endedAt: Instant?,
    val serverNow: Instant,
    val profile: ObsScoreboardProfile,
)
data class ScoreboardProfileChanged(val matchId: UUID)
