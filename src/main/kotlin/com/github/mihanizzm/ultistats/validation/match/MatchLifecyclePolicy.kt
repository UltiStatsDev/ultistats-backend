package com.github.mihanizzm.ultistats.validation.match

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchStatus
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class MatchLifecyclePolicy {
    fun validateUpdate(match: Match): MatchLifecycleDecision = when (match.status) {
        MatchStatus.PLANNED -> MatchLifecycleDecision.Allowed
        MatchStatus.IN_PROGRESS, MatchStatus.FINISHED -> invalidState(match, MatchProblemCode.MATCH_UPDATE_LOCKED, "Match state conflict", "Match details can only be updated while the match is planned")
    }

    fun validateStart(match: Match, startedAt: Instant): MatchLifecycleDecision = when (match.status) {
        MatchStatus.IN_PROGRESS -> invalidState(match, MatchProblemCode.MATCH_ALREADY_STARTED, "Match state conflict", "The match has already started")
        MatchStatus.FINISHED -> invalidState(match, MatchProblemCode.MATCH_ALREADY_FINISHED, "Match state conflict", "The match has already finished")
        MatchStatus.PLANNED -> if (match.events.minOfOrNull { it.occurredAt }?.isBefore(startedAt) == true) conflict(MatchProblemCode.START_AFTER_FIRST_EVENT, "Match timestamp conflict", "The match cannot start after its first event") else MatchLifecycleDecision.Allowed
    }

    fun validateFinish(match: Match, endedAt: Instant): MatchLifecycleDecision = when (match.status) {
        MatchStatus.PLANNED -> invalidState(match, MatchProblemCode.MATCH_NOT_STARTED, "Match state conflict", "The match has not started")
        MatchStatus.FINISHED -> invalidState(match, MatchProblemCode.MATCH_ALREADY_FINISHED, "Match state conflict", "The match has already finished")
        MatchStatus.IN_PROGRESS -> when {
            endedAt.isBefore(requireNotNull(match.effectiveStartedAt)) -> conflict(MatchProblemCode.END_BEFORE_START, "Match timestamp conflict", "The match cannot finish before it starts")
            match.events.maxOfOrNull { it.occurredAt }?.isAfter(endedAt) == true -> conflict(MatchProblemCode.END_BEFORE_LAST_EVENT, "Match timestamp conflict", "The match cannot finish before its last event")
            else -> MatchLifecycleDecision.Allowed
        }
    }

    fun validateEventCreation(match: Match, occurredAt: Instant): MatchLifecycleDecision = when (match.status) {
        MatchStatus.PLANNED, MatchStatus.FINISHED -> invalidState(match, MatchProblemCode.MATCH_NOT_IN_PROGRESS, "Match state conflict", "Events can only be created while the match is in progress")
        MatchStatus.IN_PROGRESS -> when {
            occurredAt.isBefore(requireNotNull(match.effectiveStartedAt)) -> conflict(MatchProblemCode.EVENT_BEFORE_START, "Match timestamp conflict", "An event cannot occur before the match starts")
            match.events.maxOfOrNull { it.occurredAt }?.isAfter(occurredAt) == true -> conflict(MatchProblemCode.EVENT_OUT_OF_ORDER, "Match timestamp conflict", "An event cannot occur before the latest event")
            else -> MatchLifecycleDecision.Allowed
        }
    }

    fun validateEventUpdate(match: Match): MatchLifecycleDecision = validateEventModification(match)

    fun validateEventDeletion(match: Match): MatchLifecycleDecision = validateEventModification(match)

    private fun validateEventModification(match: Match): MatchLifecycleDecision = when (match.status) {
        MatchStatus.PLANNED -> invalidState(match, MatchProblemCode.MATCH_NOT_IN_PROGRESS, "Match state conflict", "Events can only be changed after the match starts")
        MatchStatus.IN_PROGRESS, MatchStatus.FINISHED -> MatchLifecycleDecision.Allowed
    }

    private fun invalidState(match: Match, code: MatchProblemCode, title: String, detail: String) =
        MatchLifecycleDecision.InvalidState(MatchProblem(code, title, detail, match.status))

    private fun conflict(code: MatchProblemCode, title: String, detail: String) =
        MatchLifecycleDecision.Conflict(MatchProblem(code, title, detail))
}
