package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.EventCategory
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import com.github.mihanizzm.ultistats.service.CompletionHistoryEntry
import com.github.mihanizzm.ultistats.service.PointCompletionHistoryService
import com.github.mihanizzm.ultistats.service.statistics.StatisticsEvent.Source
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Suppress("unused")
class StatisticsServiceImpl(
    private val statisticsAggregatorList: List<StatisticsAggregator>,
    private val announcements: MatchAnnouncementRepository,
    private val pointLineups: PointLineupRepository,
    private val history: PointCompletionHistoryService,
) : StatisticsService {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun recalculateMatchStatistics(match: Match): MatchStatistics {
        val roster = match.participantsByTeam.values.flatten().associateBy { it.participantId }
        val slots = pointLineups.slots(match.id)
        val teamByParticipantId = roster.mapValues { it.value.teamId } + slots.mapValues { it.value.teamId }
        val identified = slots.mapNotNull { (slotId, slot) ->
            val player = roster[slot.resolvedParticipantId]
            if (player?.kind == MatchParticipantKind.PLAYER && player.teamId == slot.teamId) {
                slotId to player.participantId
            } else null
        }.toMap()
        val initialStatistics = MatchStatistics(
            playerStatistics = teamByParticipantId.keys.map { PlayerStatistics(participantId = it) },
            teamStatistics = match.teamIds.map { TeamStatistics(teamId = it) },
        )

        // Slot UUIDs are match-wide unique and journal validation keeps references in their original point.
        fun effectiveActor(id: UUID): UUID = identified[id] ?: id
        val projected = history.approvedProjection(match)
        val events = projected?.map { entry ->
            when (entry) {
                is CompletionHistoryEntry.Original -> StatisticsEvent.original(entry.event.event).let { event ->
                    if (event.type == EventType.POINT_RESULT) event.copy(occurredAt = null, source = Source.RESULT_MARKER)
                    else event
                }
                is CompletionHistoryEntry.Restored -> entry.insertion.let { action ->
                    StatisticsEvent(
                        action.type.eventType(),
                        action.elapsedOffsetMs?.let(requireNotNull(match.effectiveStartedAt)::plusMillis),
                        action.fromActorId,
                        action.toActorId,
                        source = Source.RESTORED,
                    )
                }
            }
        } ?: match.events.map(StatisticsEvent::original)
        val attributed = if (identified.isEmpty()) events else events.map { event ->
            when (event.type.category) {
                EventCategory.ONE_PLAYER -> event.copy(fromActorId = effectiveActor(event.fromActor))
                EventCategory.TWO_PLAYER -> event.copy(
                    fromActorId = effectiveActor(event.fromActor),
                    toActorId = effectiveActor(event.toActor),
                )
                else -> event
            }
        }
        val intervals = announcements.list(match.id)
        return statisticsAggregatorList.fold(initialStatistics) { stats, aggregator ->
            aggregator.aggregateObservations(stats, attributed, teamByParticipantId, intervals)
        }
    }
}
