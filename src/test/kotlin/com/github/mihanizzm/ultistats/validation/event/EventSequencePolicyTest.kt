package com.github.mihanizzm.ultistats.validation.event

import com.github.mihanizzm.ultistats.model.events.AnnouncementStartEvent
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.assertIs

@Suppress("NonAsciiCharacters")
class EventSequencePolicyTest {
    private val policy = EventSequencePolicy()

    @ParameterizedTest(name = "{0}")
    @MethodSource("stateCases")
    fun `матрица разрешает только переходы текущего состояния`(case: StateCase) {
        val current = assertIs<EventSequenceDecision.Allowed>(policy.validate(case.prefix))
        assertThat(current.state.code).isEqualTo(case.stateCode)

        EventType.entries.forEach { attemptedType ->
            val result = policy.validate(case.prefix + event(attemptedType, case.prefix.size))
            if (attemptedType in case.allowed) {
                assertThat(result)
                    .describedAs("%s must be allowed from %s", attemptedType, case.stateCode)
                    .isInstanceOf(EventSequenceDecision.Allowed::class.java)
            } else {
                val rejected = assertIs<EventSequenceDecision.Rejected>(result)
                assertThat(rejected.violation.currentState).isEqualTo(case.stateCode)
                assertThat(rejected.violation.attemptedType).isEqualTo(attemptedType)
                assertThat(rejected.violation.eventIndex).isEqualTo(case.prefix.size)
            }
        }
    }

    @Test
    fun `таймаут во время владения восстанавливает владение`() {
        val events = events(PULL, PICKUP, PASS, TIMEOUT_START, TIMEOUT_END, GOAL)

        val result = assertIs<EventSequenceDecision.Allowed>(policy.validate(events))

        assertThat(result.state.code).isEqualTo("POINT_ENDED")
    }

    @Test
    fun `таймаут между поинтами восстанавливает завершенный поинт`() {
        val events = events(PULL, PICKUP, GOAL, TIMEOUT_START, TIMEOUT_END)

        val result = assertIs<EventSequenceDecision.Allowed>(
            policy.validate(events, requirePointEnded = true),
        )

        assertThat(result.state.code).isEqualTo("POINT_ENDED")
    }

    @Test
    fun `завершенный матч отклоняет корректный но незавершенный префикс`() {
        val result = assertIs<EventSequenceDecision.Rejected>(
            policy.validate(events(PULL, PICKUP, PASS), requirePointEnded = true),
        )

        assertThat(result.violation.currentState).isEqualTo("POSSESSION_ACTIVE")
        assertThat(result.violation.attemptedType).isNull()
        assertThat(result.violation.eventIndex).isEqualTo(3)
    }

    data class StateCase(
        val name: String,
        val prefix: List<Event>,
        val stateCode: String,
        val allowed: Set<EventType>,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val PARTICIPANT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val PARTICIPANT_2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val TEAM = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val OCCURRED_AT = Instant.parse("2026-08-25T08:00:00Z")

        private val activeEvents = setOf(
            PASS,
            GOAL,
            INCOMPLETE_PASS,
            BLOCK,
            BLOCK_MARKER,
            BLOCK_FIELD,
            INTERCEPTION,
            CALLAHAN,
            TIMEOUT_START,
            ANNOUNCEMENT_START,
        )

        @JvmStatic
        fun stateCases(): Stream<Arguments> = Stream.of(
            state("до первого пулла", emptyList(), "BEFORE_FIRST_PULL", setOf(PULL, TIMEOUT_START)),
            state("пулл в полете", events(PULL), "PULL_IN_FLIGHT", setOf(BRICK, PICKUP, ANNOUNCEMENT_START)),
            state("ожидание подбора после брика", events(PULL, BRICK), "PICKUP_REQUIRED", setOf(PICKUP, ANNOUNCEMENT_START)),
            state("активное владение после подбора", events(PULL, PICKUP), "POSSESSION_ACTIVE", activeEvents),
            state("активное владение после паса", events(PULL, PICKUP, PASS), "POSSESSION_ACTIVE", activeEvents),
            state("активное владение после перехвата", events(PULL, PICKUP, INTERCEPTION), "POSSESSION_ACTIVE", activeEvents),
            state("ожидание подбора после незавершенного паса", events(PULL, PICKUP, INCOMPLETE_PASS), "PICKUP_REQUIRED", setOf(PICKUP, ANNOUNCEMENT_START)),
            state("ожидание подбора после общего блока", events(PULL, PICKUP, BLOCK), "PICKUP_REQUIRED", setOf(PICKUP, ANNOUNCEMENT_START)),
            state("ожидание подбора после marker блока", events(PULL, PICKUP, BLOCK_MARKER), "PICKUP_REQUIRED", setOf(PICKUP, ANNOUNCEMENT_START)),
            state("ожидание подбора после field блока", events(PULL, PICKUP, BLOCK_FIELD), "PICKUP_REQUIRED", setOf(PICKUP, ANNOUNCEMENT_START)),
            state(
                "завершенный поинт",
                events(PULL, PICKUP, GOAL),
                "POINT_ENDED",
                setOf(PULL, TIMEOUT_START, HALFTIME_START),
            ),
            state(
                "завершенный поинт после кэллахана",
                events(PULL, PICKUP, CALLAHAN),
                "POINT_ENDED",
                setOf(PULL, TIMEOUT_START, HALFTIME_START),
            ),
            state("халфтайм", events(PULL, PICKUP, GOAL, HALFTIME_START), "HALFTIME", setOf(HALFTIME_END)),
            state(
                "после халфтайма",
                events(PULL, PICKUP, GOAL, HALFTIME_START, HALFTIME_END),
                "AFTER_HALFTIME",
                setOf(PULL, TIMEOUT_START),
            ),
            state("объявление во время пулла", events(PULL, ANNOUNCEMENT_START), "ANNOUNCEMENT", setOf(ANNOUNCEMENT_END)),
            state("пулл после объявления", events(PULL, ANNOUNCEMENT_START, ANNOUNCEMENT_END), "PULL_IN_FLIGHT",
                setOf(BRICK, PICKUP, ANNOUNCEMENT_START)),
            state("владение после объявления", events(PULL, PICKUP, ANNOUNCEMENT_START, ANNOUNCEMENT_END),
                "POSSESSION_ACTIVE", activeEvents),
            state("таймаут до первого пулла", events(TIMEOUT_START), "TIMEOUT", setOf(TIMEOUT_END)),
            state("таймаут во время владения", events(PULL, PICKUP, TIMEOUT_START), "TIMEOUT", setOf(TIMEOUT_END)),
            state("таймаут между поинтами", events(PULL, PICKUP, GOAL, TIMEOUT_START), "TIMEOUT", setOf(TIMEOUT_END)),
        ).map { Arguments.of(it) }

        private fun state(
            name: String,
            prefix: List<Event>,
            stateCode: String,
            allowed: Set<EventType>,
        ) = StateCase(name, prefix, stateCode, allowed)

        private fun events(vararg types: EventType): List<Event> = types.mapIndexed(::event)

        private fun event(index: Int, type: EventType): Event = event(type, index)

        private fun event(type: EventType, index: Int): Event {
            val occurredAt = OCCURRED_AT.plusSeconds(index.toLong())
            return when (type.category) {
                EventCategory.ONE_PLAYER -> OnePlayerEvent(PARTICIPANT_1, occurredAt, type)
                EventCategory.TWO_PLAYER -> TwoPlayerEvent(PARTICIPANT_1, PARTICIPANT_2, occurredAt, type)
                EventCategory.TEAM -> TeamEvent(TEAM, occurredAt, type)
                EventCategory.ANNOUNCEMENT -> AnnouncementStartEvent(null, occurredAt)
                EventCategory.SYSTEM -> SystemEvent(occurredAt, type)
            }
        }
    }
}

private val PASS = EventType.PASS
private val GOAL = EventType.GOAL
private val INCOMPLETE_PASS = EventType.INCOMPLETE_PASS
private val PULL = EventType.PULL
private val BRICK = EventType.BRICK
private val PICKUP = EventType.PICKUP
private val BLOCK = EventType.BLOCK
private val BLOCK_MARKER = EventType.BLOCK_MARKER
private val BLOCK_FIELD = EventType.BLOCK_FIELD
private val INTERCEPTION = EventType.INTERCEPTION
private val CALLAHAN = EventType.CALLAHAN
private val TIMEOUT_START = EventType.TIMEOUT_START
private val TIMEOUT_END = EventType.TIMEOUT_END
private val HALFTIME_START = EventType.HALFTIME_START
private val HALFTIME_END = EventType.HALFTIME_END

private val ANNOUNCEMENT_START = EventType.ANNOUNCEMENT_START
private val ANNOUNCEMENT_END = EventType.ANNOUNCEMENT_END
