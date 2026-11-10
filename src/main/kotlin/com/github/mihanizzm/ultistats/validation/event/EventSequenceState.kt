package com.github.mihanizzm.ultistats.validation.event

/** Logical state derived exclusively from the ordered active event log. */
sealed interface EventSequenceState {
    val code: String

    data object BeforeFirstPull : EventSequenceState {
        override val code: String = "BEFORE_FIRST_PULL"
    }

    data object PointEnded : EventSequenceState {
        override val code: String = "POINT_ENDED"
    }

    data object AfterHalftime : EventSequenceState {
        override val code: String = "AFTER_HALFTIME"
    }

    data object PullInFlight : EventSequenceState {
        override val code: String = "PULL_IN_FLIGHT"
    }

    data object PickupRequired : EventSequenceState {
        override val code: String = "PICKUP_REQUIRED"
    }

    data object PossessionActive : EventSequenceState {
        override val code: String = "POSSESSION_ACTIVE"
    }

    data object Halftime : EventSequenceState {
        override val code: String = "HALFTIME"
    }

    data class Announcement(val resume: EventSequenceState) : EventSequenceState {
        override val code: String = "ANNOUNCEMENT"
    }

    data class Timeout(val resume: EventSequenceState) : EventSequenceState {
        override val code: String = "TIMEOUT"
    }
}
