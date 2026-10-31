package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Immutable historical command result. This is NOT the current canonical log. GET v2 events separately before resuming input.")
@JsonInclude(JsonInclude.Include.ALWAYS)
data class UndoCommandResponse(
    val matchId: UUID,
    val commandId: UUID,
    val expectedLastEventId: UUID,
    val expectedLogRevision: Long,
    val outcome: UndoCommandOutcome,
    @field:Schema(description = "Committed revision for APPLIED; observed revision for REJECTED. Never refreshed on replay.")
    val resultLogRevision: Long,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val removedEventId: UUID?,
    @field:Schema(types = ["integer", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val removedSequenceNumber: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Null for APPLIED; immutable rejection code and detail for REJECTED.")
    val rejection: UndoCommandRejection?,
)

enum class UndoCommandOutcome { APPLIED, REJECTED }

data class UndoCommandRejection(val code: MatchProblemCode, val detail: String)
