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
    @field:Schema(description = "Whether point lineup writes permit editing a confirmed NEXT point.")
    val nextLineupEditsWriteEnabled: Boolean,
    @field:Schema(description = "Whether point lineup writes permit correcting known starters on completed points.")
    val starterCorrectionsWriteEnabled: Boolean,
    @field:Schema(description = "True when this server supports capability 4 reads, independently of the writer flag.")
    val pointResultsReadSupported: Boolean,
    @field:Schema(description = "Whether fresh point result commands are enabled. Authorization is still required.")
    val pointResultsWriteEnabled: Boolean,
    @field:Schema(description = "Whether this server supports protected capability 4 completion draft reads.")
    val pointCompletionDraftsReadSupported: Boolean,
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
