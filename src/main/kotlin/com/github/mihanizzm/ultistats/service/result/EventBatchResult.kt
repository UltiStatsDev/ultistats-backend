package com.github.mihanizzm.ultistats.service.result

import com.github.mihanizzm.ultistats.dto.response.EventLogResponse
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import java.util.UUID

sealed interface EventBatchResult {
    data class Success(val response: EventLogResponse, val replayed: Boolean = false) : EventBatchResult
    data object NotFound : EventBatchResult
    data class Rejected(
        val problem: MatchProblem,
        val lastSequenceNumber: Int,
        val logRevision: Long,
        val eventId: UUID? = null,
        val eventIndex: Int? = null,
        val badRequest: Boolean = false,
    ) : EventBatchResult
}
