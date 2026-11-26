package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.statistics.AnnouncementStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.MatchTimeStatistics
import com.github.mihanizzm.ultistats.service.statistics.StatisticsEvent.Source
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
@Suppress("unused")
class TotalTimeStatisticsAggregator : StatisticsAggregator {
    override fun aggregateObservations(
        previousStatisticsState: MatchStatistics,
        events: List<StatisticsEvent>,
        teamByParticipantId: Map<UUID, UUID>,
        announcements: List<MatchAnnouncement>,
    ): MatchStatistics {
        var lastEventTime: Instant? = null
        var awaitingTimedPull = false
        var immediatelyAfterRestoredFinish = false
        var currentTeamPossessing: UUID? = null
        var currentParticipantPossessing: UUID? = null
        var isStopped = false
        var timeoutStart: StatisticsEvent? = null
        var announcementStart: StatisticsEvent? = null
        val announcementTotals = mutableMapOf<UUID?, AnnouncementStatistics>()
        val announcementCoverage = AnnouncementCoverage(announcements)
        var standaloneOverlap = Duration.ZERO
        var overlapWithExistingPauses = Duration.ZERO

        fun countAnnouncement(teamId: UUID?, duration: Duration) {
            val previous = announcementTotals[teamId] ?: AnnouncementStatistics()
            announcementTotals[teamId] = AnnouncementStatistics(previous.count + 1, previous.duration.plus(duration))
        }

        fun recordExistingPause(start: Instant?, end: Instant) {
            overlapWithExistingPauses = overlapWithExistingPauses.plus(announcementCoverage.overlap(start, end))
        }
        var lastBetweenPointsStart: Instant? = null

        val teamPossessionTime = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val teamTimeBetweenPoints = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val participantPossessionTime = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        val timeSpentOnTimeouts = mutableMapOf<UUID, Duration>().withDefault { Duration.ZERO }
        // Accumulate a hold separately: an unfinished suffix remains in total possession
        // time, but must never enter the completed-hold average.
        var holdingDuration = Duration.ZERO
        var fullyObservedHold = true
        val completedHoldingDuration = mutableMapOf<UUID, Duration>()
        val completedHoldCount = mutableMapOf<UUID, Long>()
        var totalMatchTime = Duration.ZERO
        var timeSpentOnHalftime = Duration.ZERO

        for (event in events) {
            // The adjacent restored result already supplied the end of play, even if its clock is unknown.
            if (event.source == Source.RESULT_MARKER && immediatelyAfterRestoredFinish) {
                immediatelyAfterRestoredFinish = false
                continue
            }
            immediatelyAfterRestoredFinish = event.source == Source.RESTORED && event.type.closesPoint

            // Across a team result, only explicitly timed pauses count until a pull starts observed play.
            val previousEventTime = lastEventTime?.takeIf {
                event.type != EventType.POINT_RESULT && (!awaitingTimedPull || isStopped)
            }
            val eventTime = event.occurredAt
            var duration = Duration.ZERO
            if (previousEventTime != null && eventTime != null) {
                duration = Duration.between(previousEventTime, eventTime)
                totalMatchTime = totalMatchTime.plus(duration)

                val standalone = announcementCoverage.overlap(previousEventTime, eventTime)
                standaloneOverlap = standaloneOverlap.plus(standalone)
                if (!isStopped) {
                    val active = duration.minus(standalone)
                    currentTeamPossessing?.let { team -> teamPossessionTime.merge(team, active, Duration::plus) }
                    currentParticipantPossessing?.let { participant ->
                        participantPossessionTime.merge(participant, active, Duration::plus)
                        holdingDuration = holdingDuration.plus(active)
                    }
                }
            } else if (!isStopped && currentParticipantPossessing != null && (lastEventTime == null || eventTime == null)) {
                fullyObservedHold = false
            }

            if (eventTime == null) lastBetweenPointsStart = null
            lastEventTime = eventTime

            val completesHold = when (event.type) {
                EventType.PASS, EventType.GOAL, EventType.PICKUP,
                EventType.BLOCK, EventType.BLOCK_MARKER, EventType.BLOCK_FIELD,
                EventType.INCOMPLETE_PASS, EventType.INTERCEPTION, EventType.CALLAHAN,
                EventType.HALFTIME_START -> true
                EventType.POSSESSION_SET -> currentParticipantPossessing != event.fromActor
                EventType.PLAYER_SUBSTITUTION -> currentParticipantPossessing == event.fromActor
                else -> false // Timeouts and announcements pause the hold without completing it.
            }
            if (completesHold) {
                currentParticipantPossessing?.takeIf { fullyObservedHold }?.let { participant ->
                    completedHoldingDuration.merge(participant, holdingDuration, Duration::plus)
                    completedHoldCount.merge(participant, 1L, Long::plus)
                }
                holdingDuration = Duration.ZERO
                fullyObservedHold = true
            }

            when (event.type) {
                EventType.PLAYER_SUBSTITUTION -> {
                    if (completesHold) currentParticipantPossessing = event.toActor
                }
                EventType.PASS -> {
                    currentParticipantPossessing = event.toActor
                }
                EventType.POINT_RESULT -> {
                    currentParticipantPossessing = null
                    currentTeamPossessing = null
                    holdingDuration = Duration.ZERO
                    fullyObservedHold = true
                    lastBetweenPointsStart = null
                    awaitingTimedPull = event.source == Source.JOURNAL
                    isStopped = false
                }
                EventType.GOAL -> {
                    currentParticipantPossessing = null
                    currentTeamPossessing = null
                    isStopped = true
                    // Начинаем отсчет времени между поинтами от момента гола
                    lastBetweenPointsStart = eventTime
                }
                EventType.PULL -> {
                    val pullTeam = teamByParticipantId.getValue(event.fromActor)
                    // Добавляем во время между поинтами только чистый отрезок между окончанием предыдущего поинта
                    // и текущим пуллом, исключая таймауты и халфтайм.
                    if (eventTime != null) {
                        val between = lastBetweenPointsStart?.let { Duration.between(it, eventTime) } ?: duration
                        if (!between.isNegative && !between.isZero) {
                            teamTimeBetweenPoints.merge(pullTeam, between, Duration::plus)
                            recordExistingPause(lastBetweenPointsStart ?: previousEventTime, eventTime)
                        }
                    }
                    // После пулла отсчет между поинтами завершен
                    awaitingTimedPull = false
                    lastBetweenPointsStart = null
                    isStopped = false
                }
                EventType.BRICK -> { }
                EventType.PICKUP, EventType.POSSESSION_SET -> {
                    currentTeamPossessing = teamByParticipantId.getValue(event.fromActor)
                    currentParticipantPossessing = event.fromActor
                }
                EventType.BLOCK, EventType.BLOCK_MARKER, EventType.BLOCK_FIELD, EventType.INCOMPLETE_PASS -> {
                    currentTeamPossessing = null
                    currentParticipantPossessing = null
                }
                EventType.INTERCEPTION -> {
                    currentTeamPossessing = teamByParticipantId.getValue(event.toActor)
                    currentParticipantPossessing = event.toActor
                }
                EventType.CALLAHAN -> {
                    currentTeamPossessing = null
                    currentParticipantPossessing = null
                    isStopped = true
                    // Кэллахан завершает поинт
                    lastBetweenPointsStart = eventTime
                }
                EventType.ANNOUNCEMENT_START -> {
                    announcementStart = event
                    isStopped = true
                }
                EventType.ANNOUNCEMENT_END -> {
                    announcementStart?.let { start ->
                        val end = requireNotNull(eventTime)
                        countAnnouncement(start.teamId, Duration.between(start.occurredAt, end))
                        recordExistingPause(start.occurredAt, end)
                        isStopped = false
                    }
                    announcementStart = null
                }
                EventType.TIMEOUT_START -> {
                    timeoutStart = event
                    isStopped = true
                }
                EventType.TIMEOUT_END -> {
                    isStopped = false
                    timeoutStart?.let { start ->
                        val end = requireNotNull(eventTime)
                        timeSpentOnTimeouts.merge(requireNotNull(start.teamId), Duration.between(start.occurredAt, end), Duration::plus)
                        recordExistingPause(start.occurredAt, end)
                    }
                    timeoutStart = null
                    // Между поинтами не должно включать время таймаута, поэтому старт переносим на конец таймаута
                    if (lastBetweenPointsStart != null) {
                        lastBetweenPointsStart = eventTime
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
                    recordExistingPause(previousEventTime, requireNotNull(eventTime))
                    // Между поинтами не должно включать время халфтайма, поэтому старт переносим на конец халфтайма
                    if (lastBetweenPointsStart != null) {
                        lastBetweenPointsStart = eventTime
                    }
                }
            }
        }

        val totalTimeBetweenPoints = teamTimeBetweenPoints.values.fold(Duration.ZERO, Duration::plus)
        val totalTimeOnTimeouts = timeSpentOnTimeouts.values.fold(Duration.ZERO, Duration::plus)
        // Subtract standalone coverage only inside measured spans and outside other pauses.
        val standaloneTime = standaloneOverlap.minus(overlapWithExistingPauses)
        val purePlayTime = totalMatchTime
            .minus(totalTimeBetweenPoints)
            .minus(totalTimeOnTimeouts)
            .minus(timeSpentOnHalftime)
            .minus(announcementTotals.values.fold(Duration.ZERO) { sum, item -> sum.plus(item.duration) })
            .minus(standaloneTime)
        for (announcement in announcements) {
            announcement.endedAt?.let { end ->
                countAnnouncement(announcement.teamId, Duration.between(announcement.startedAt, end))
            }
        }

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
                announcements = announcementTotals.toMap(),
            ),
        )
    }
}

/** The repository provides ordered, nonoverlapping intervals. Zero-length records only affect counts. */
private class AnnouncementCoverage(announcements: List<MatchAnnouncement>) {
    private val intervals = announcements.filter { it.startedAt != it.endedAt }

    fun overlap(start: Instant?, end: Instant): Duration {
        if (start == null || start >= end || intervals.isEmpty()) return Duration.ZERO

        // A call that started before this span may still be active at its beginning.
        val exactOrInsertion = intervals.binarySearch { it.startedAt.compareTo(start) }
        val first = if (exactOrInsertion >= 0) exactOrInsertion else (-exactOrInsertion - 2).coerceAtLeast(0)
        var duration = Duration.ZERO
        for (index in first until intervals.size) {
            val interval = intervals[index]
            if (interval.startedAt >= end) break
            val overlapStart = maxOf(start, interval.startedAt)
            val overlapEnd = minOf(end, interval.endedAt ?: end)
            if (overlapStart < overlapEnd) duration = duration.plus(Duration.between(overlapStart, overlapEnd))
        }
        return duration
    }
}
