package com.github.mihanizzm.ultistats.validation.event

import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType

/** Pure finite-state validation of an ordered active match-event log. */
class EventSequencePolicy {
    fun validate(
        events: List<Event>,
        requirePointEnded: Boolean = false,
    ): EventSequenceDecision {
        var state: EventSequenceState = EventSequenceState.BeforeFirstPull
        events.forEachIndexed { index, event ->
            state = transition(state, event.type) ?: return rejected(index, state, event.type)
        }
        return if (requirePointEnded && state != EventSequenceState.PointEnded) {
            rejected(events.size, state, null)
        } else {
            EventSequenceDecision.Allowed(state)
        }
    }

    private fun transition(
        state: EventSequenceState,
        type: EventType,
    ): EventSequenceState? = when (state) {
        EventSequenceState.BeforeFirstPull -> when (type) {
            EventType.PULL -> EventSequenceState.PullInFlight
            EventType.TIMEOUT_START -> EventSequenceState.Timeout(state)
            else -> null
        }
        EventSequenceState.PointEnded -> when (type) {
            EventType.PULL -> EventSequenceState.PullInFlight
            EventType.TIMEOUT_START -> EventSequenceState.Timeout(state)
            EventType.HALFTIME_START -> EventSequenceState.Halftime
            else -> null
        }
        EventSequenceState.AfterHalftime -> when (type) {
            EventType.PULL -> EventSequenceState.PullInFlight
            EventType.TIMEOUT_START -> EventSequenceState.Timeout(state)
            else -> null
        }
        EventSequenceState.PullInFlight -> when (type) {
            EventType.PLAYER_SUBSTITUTION -> state
            EventType.ANNOUNCEMENT_START -> EventSequenceState.Announcement(state)
            EventType.BRICK -> EventSequenceState.PickupRequired
            EventType.PICKUP, EventType.POSSESSION_SET -> EventSequenceState.PossessionActive
            else -> null
        }
        EventSequenceState.PickupRequired -> when (type) {
            EventType.PLAYER_SUBSTITUTION -> state
            EventType.ANNOUNCEMENT_START -> EventSequenceState.Announcement(state)
            EventType.PICKUP, EventType.POSSESSION_SET -> EventSequenceState.PossessionActive
            else -> null
        }
        EventSequenceState.PossessionActive -> when (type) {
            EventType.PLAYER_SUBSTITUTION -> state
            EventType.ANNOUNCEMENT_START -> EventSequenceState.Announcement(state)
            EventType.PASS, EventType.INTERCEPTION, EventType.POSSESSION_SET -> EventSequenceState.PossessionActive
            EventType.GOAL, EventType.CALLAHAN -> EventSequenceState.PointEnded
            EventType.INCOMPLETE_PASS,
            EventType.BLOCK,
            EventType.BLOCK_MARKER,
            EventType.BLOCK_FIELD,
            -> EventSequenceState.PickupRequired
            EventType.TIMEOUT_START -> EventSequenceState.Timeout(state)
            else -> null
        }
        EventSequenceState.Halftime -> when (type) {
            EventType.HALFTIME_END -> EventSequenceState.AfterHalftime
            else -> null
        }
        is EventSequenceState.Announcement -> when (type) {
            EventType.ANNOUNCEMENT_END -> state.resume
            EventType.PLAYER_SUBSTITUTION -> state
            else -> null
        }
        is EventSequenceState.Timeout -> when (type) {
            EventType.TIMEOUT_END -> state.resume
            EventType.PLAYER_SUBSTITUTION -> state.takeIf { it.resume == EventSequenceState.PossessionActive }
            else -> null
        }
    }

    private fun rejected(
        eventIndex: Int,
        state: EventSequenceState,
        attemptedType: EventType?,
    ) = EventSequenceDecision.Rejected(
        EventSequenceViolation(eventIndex, state.code, attemptedType),
    )
}
