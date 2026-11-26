package com.github.mihanizzm.ultistats.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointResultResponse(
    val replayed: Boolean,
    @field:Schema(description = "Canonical POINT_RESULT. Its occurredAt is the server registration time.")
    val marker: TeamEventResponse,
    val pointId: UUID,
    @field:Schema(description = "Stable successor ID derived from the marker, including on a later retry.")
    val nextPointId: UUID,
    @field:Schema(description = "The full current canonical journal, including on an exact retry.")
    val log: EventLogResponse,
)
