package com.github.mihanizzm.ultistats.dto.response

import java.util.UUID

data class PointCompletionApprovalResponse(
    val operationId: UUID,
    val pointId: UUID,
    val closingEventId: UUID,
    val draftRevision: Long,
    val logRevision: Long,
    val lastSequenceNumber: Int,
    val replayed: Boolean,
)
