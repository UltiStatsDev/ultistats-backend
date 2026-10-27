package com.github.mihanizzm.ultistats.validation.match

import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.model.events.EventType

enum class MatchProblemCode {
    INVALID_REQUEST,
    RESOURCE_NOT_FOUND,
    MATCH_NOT_IN_PROGRESS,
    MATCH_ALREADY_STARTED,
    MATCH_NOT_STARTED,
    MATCH_ALREADY_FINISHED,
    MATCH_UPDATE_LOCKED,
    START_AFTER_FIRST_EVENT,
    END_BEFORE_START,
    END_BEFORE_LAST_EVENT,
    EVENT_BEFORE_START,
    EVENT_OUT_OF_ORDER,
    EVENT_IDEMPOTENCY_CONFLICT,
    EVENT_SEQUENCE_VIOLATION,
    MATCH_NOT_AT_POINT_END,
}

data class MatchProblem(
    val code: MatchProblemCode,
    val title: String,
    val detail: String,
    val currentStatus: MatchStatus? = null,
    val currentState: String? = null,
    val attemptedEventType: EventType? = null,
)
