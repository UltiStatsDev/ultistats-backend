package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.events.PointStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointLineupsResponse(
    val logRevision: Long,
    val writesEnabled: Boolean,
    val points: List<PointLineupResponse>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PointLineupResponse(
    val pointId: UUID,
    val number: Int,
    val status: PointStatus,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val pullEventId: UUID?,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val closingEventId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val lineup: PointLineup?,
)
