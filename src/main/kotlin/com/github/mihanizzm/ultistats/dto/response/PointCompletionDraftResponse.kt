package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PointCompletionDraftResponse(
    val matchId: UUID,
    val pointId: UUID,
    val marker: TeamEventResponse,
    val logRevision: Long,
    @field:Schema(types = ["integer", "null"], format = "int64", requiredMode = Schema.RequiredMode.REQUIRED)
    val lineupRevision: Long?,
    val draftRevision: Long,
    val draft: PointCompletionDraft?,
    val preview: List<PointCompletionPreviewEntry>,
    val diagnostics: List<PointCompletionDiagnostic>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PointCompletionDraft(
    val closingEventId: UUID,
    val baseLogRevision: Long,
    @field:Schema(types = ["integer", "null"], format = "int64", requiredMode = Schema.RequiredMode.REQUIRED)
    val baseLineupRevision: Long?,
    val insertions: List<PointCompletionInsertion>,
)

data class PointCompletionPreviewEntry(
    val original: EventResponse? = null,
    val insertion: PointCompletionInsertion? = null,
)

data class PointCompletionDiagnostic(
    val code: PointCompletionDiagnosticCode,
    val detail: String,
    val eventId: UUID? = null,
)

enum class PointCompletionDiagnosticCode {
    DRAFT_BASE_STALE, ANCHOR_UNAVAILABLE, ACTOR_UNAVAILABLE, EVENT_ID_IN_USE,
    MISSING_PULL, MISSING_PERSONAL_RESULT, EVENT_ORDER, CLOCK_OUT_OF_BOUNDS, CLOCK_OUT_OF_ORDER,
    ACTOR_NOT_ON_FIELD, ACTOR_TEAM_CONFLICT, POSSESSION_CONFLICT, SCORING_TEAM_CONFLICT,
}
