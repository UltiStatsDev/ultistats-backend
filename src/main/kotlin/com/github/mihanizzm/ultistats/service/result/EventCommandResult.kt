package com.github.mihanizzm.ultistats.service.result

import com.github.mihanizzm.ultistats.model.events.StoredEvent
import com.github.mihanizzm.ultistats.validation.match.MatchProblem

sealed interface EventCommandResult {
    data class Success(val event: StoredEvent, val replayed: Boolean = false) : EventCommandResult
    data object Deleted : EventCommandResult
    data object NotFound : EventCommandResult
    data class InvalidState(val problem: MatchProblem) : EventCommandResult
    data class Conflict(val problem: MatchProblem) : EventCommandResult
}
