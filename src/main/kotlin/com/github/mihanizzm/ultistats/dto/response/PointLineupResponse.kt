package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.PointLineup
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import com.github.mihanizzm.ultistats.model.events.PointStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointLineupsResponse(
    val logRevision: Long,
    val writesEnabled: Boolean,
    val substitutionsWriteEnabled: Boolean,
    @field:Schema(description = "Capability 3 discovery. True when all write gates permit creating an incoming reserve.")
    val incomingReservesWriteEnabled: Boolean,
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
    @field:Schema(
        description = "Teams after replaying this point's substitutions in journal order. Null until confirmation.",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val effectiveTeams: List<PointLineupTeam>?,
    @field:Schema(description = "Temporary slots introduced by active events in this point, including reserves who have since left.")
    val substitutionSlots: List<PointSubstitutionSlot>,
)
