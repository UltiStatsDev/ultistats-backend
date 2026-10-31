package com.github.mihanizzm.ultistats.dto.response.statistics

import io.swagger.v3.oas.annotations.media.Schema
import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ParticipantStatisticsResponse(
    val participantId: UUID,
    val kind: MatchParticipantKind,
    @field:Schema(types = ["integer", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val unknownSlot: Int?,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val firstName: String?,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val lastName: String?,
    val displayName: String,
    @field:Schema(types = ["integer", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val number: Int?,
    val attack: PlayerAttackStatisticsResponse,
    val defense: DefenseStatisticsResponse,
    val time: PlayerTimeStatisticsResponse,
)
