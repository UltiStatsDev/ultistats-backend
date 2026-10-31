package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchTimeStatistics
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
@Suppress("unused")
class TotalTimeStatisticsAggregator : StatisticsAggregator {
    override fun aggregate(
        previousStatisticsState: MatchStatistics,
        events: List<Event>,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        var lastEventTime: Instant? = null
        var currentTeamPossessing: UUID? = null
        var currentParticipantPossessing: UUID? = null
        var isStopped = false
        var teamOnTimeout: UUID? = null
        var lastBetweenPointsStart: Instant? = null

        val teamPossessionTime = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val teamTimeBetweenPoints = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val participantPossessionTime = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val timeSpentOnTimeouts = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        // Accumulate a hold separately: an unfinished suffix remains in total possession
        // time, but must never enter the completed-hold average.
        var holdingDuration = Duration.ZERO
        val completedHoldingDuration = mutableMapOf<UUID, Duration>()
        val completedHoldCount = mutableMapOf<UUID, Long>()
        var totalMatchTime = Duration.ZERO
        var timeSpentOnHalftime = Duration.ZERO

        for (event in events) {
            var duration = Duration.ZERO
            if (lastEventTime != null) {
                duration = Duration.between(lastEventTime, event.occurredAt)
                totalMatchTime = totalMatchTime.plus(duration)

                if (currentTeamPossessing != null && !isStopped) {
                    teamPossessionTime.merge(currentTeamPossessing, duration, Duration::plus)
                }
                if (currentParticipantPossessing != null && !isStopped) {
                    participantPossessionTime.merge(currentParticipantPossessing, duration, Duration::plus)
                    holdingDuration = holdingDuration.plus(duration)
                }
            }

            lastEventTime = event.occurredAt

            when (event.type) {
                EventType.PASS, EventType.GOAL, EventType.PICKUP,
                EventType.BLOCK, EventType.BLOCK_MARKER, EventType.BLOCK_FIELD,
                EventType.INCOMPLETE_PASS, EventType.INTERCEPTION, EventType.CALLAHAN,
                EventType.HALFTIME_START -> {
                    currentParticipantPossessing?.let { participant ->
                        completedHoldingDuration.merge(participant, holdingDuration, Duration::plus)
                        completedHoldCount.merge(participant, 1L, Long::plus)
                    }
                    holdingDuration = Duration.ZERO
                }
                else -> Unit // Timeout pauses the current hold without completing it.
            }

            when (event.type) {
                EventType.PASS -> {
                    currentParticipantPossessing = (event as TwoPlayerEvent).toParticipant
                }
                EventType.GOAL -> {
                    currentParticipantPossessing = null
                    currentTeamPossessing = null
                    isStopped = true
                    // Начинаем отсчет времени между поинтами от момента гола
                    lastBetweenPointsStart = event.occurredAt
                }
                EventType.PULL -> {
                    val pullTeam = teamByParticipantId.getValue((event as OnePlayerEvent).participant)
                    // Добавляем во время между поинтами только чистый отрезок между окончанием предыдущего поинта
                    // и текущим пуллом, исключая таймауты и халфтайм.
                    val between = if (lastBetweenPointsStart != null) {
                        Duration.between(lastBetweenPointsStart, event.occurredAt)
                    } else {
                        duration
                    }
                    if (!between.isNegative && !between.isZero) {
                        teamTimeBetweenPoints.merge(pullTeam, between, Duration::plus)
                    }
                    // После пулла отсчет между поинтами завершен
                    lastBetweenPointsStart = null
                    isStopped = false
                }
                EventType.BRICK -> { }
                EventType.PICKUP -> {
                    val ope = event as OnePlayerEvent
                    currentTeamPossessing = teamByParticipantId.getValue(ope.participant)
                    currentParticipantPossessing = ope.participant
                }
                EventType.BLOCK, EventType.BLOCK_MARKER, EventType.BLOCK_FIELD, EventType.INCOMPLETE_PASS -> {
                    currentTeamPossessing = null
                    currentParticipantPossessing = null
                }
                EventType.INTERCEPTION -> {
                    val tpe = event as TwoPlayerEvent
                    currentTeamPossessing = teamByParticipantId.getValue(tpe.toParticipant)
                    currentParticipantPossessing = tpe.toParticipant
                }
                EventType.CALLAHAN -> {
                    currentTeamPossessing = null
                    currentParticipantPossessing = null
                    isStopped = true
                    // Кэллахан завершает поинт
                    lastBetweenPointsStart = event.occurredAt
                }
                EventType.TIMEOUT_START -> {
                    teamOnTimeout = (event as TeamEvent).team
                    isStopped = true
                }
                EventType.TIMEOUT_END -> {
                    isStopped = false
                    if (teamOnTimeout != null) {
                        timeSpentOnTimeouts.merge(teamOnTimeout, duration, Duration::plus)
                        teamOnTimeout = null
                    }
                    // Между поинтами не должно включать время таймаута, поэтому старт переносим на конец таймаута
                    if (lastBetweenPointsStart != null) {
                        lastBetweenPointsStart = event.occurredAt
                    }
                }
                EventType.HALFTIME_START -> {
                    currentParticipantPossessing = null
                    currentTeamPossessing = null
                    isStopped = true
                }
                EventType.HALFTIME_END -> {
                    isStopped = false
                    timeSpentOnHalftime = timeSpentOnHalftime.plus(duration)
                    // Между поинтами не должно включать время халфтайма, поэтому старт переносим на конец халфтайма
                    if (lastBetweenPointsStart != null) {
                        lastBetweenPointsStart = event.occurredAt
                    }
                }
            }
        }

        val totalTimeBetweenPoints = teamTimeBetweenPoints.values.fold(Duration.ZERO, Duration::plus)
        val totalTimeOnTimeouts = timeSpentOnTimeouts.values.fold(Duration.ZERO, Duration::plus)
        val purePlayTime = totalMatchTime
            .minus(totalTimeBetweenPoints)
            .minus(totalTimeOnTimeouts)
            .minus(timeSpentOnHalftime)

        val newPlayerStats = previousStatisticsState.playerStatistics.map { stats ->
            val possession = participantPossessionTime.getOrDefault(stats.participantId, Duration.ZERO)
            val completed = completedHoldCount.getOrDefault(stats.participantId, 0L)
            stats.copy(time = stats.time.copy(
                totalPossessionTime = possession,
                averagePossessionTime = if (completed > 0) {
                    completedHoldingDuration.getValue(stats.participantId).dividedBy(completed)
                } else Duration.ZERO,
                averagePossessionTimeAvailable = completed > 0,
            ))
        }.toMutableList()

        val newTeamStats = previousStatisticsState.teamStatistics.map { stats ->
            stats.copy(
                time = stats.time.copy(
                    totalPossessionTime = teamPossessionTime.getOrDefault(stats.teamId, Duration.ZERO),
                    totalTimeBetweenPoints = teamTimeBetweenPoints.getOrDefault(stats.teamId, Duration.ZERO),
                    totalTimeSpentOnTimeouts = timeSpentOnTimeouts.getOrDefault(stats.teamId, Duration.ZERO),
                )
            )
        }.toMutableList()

        return previousStatisticsState.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
            timeStatistics = MatchTimeStatistics(
                totalTime = totalMatchTime,
                timeSpentBetweenPoints = totalTimeBetweenPoints,
                timeSpentOnTimeouts = totalTimeOnTimeouts,
                timeSpentOnHalftime = timeSpentOnHalftime,
                pureGameTime = purePlayTime,
            ),
        )
    }
}
