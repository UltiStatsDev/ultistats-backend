package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import com.github.mihanizzm.ultistats.repository.jpa.PointLineupRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@Suppress("unused")
class StatisticsServiceImpl(
    private val statisticsAggregatorList: List<StatisticsAggregator>,
    private val announcements: MatchAnnouncementRepository,
    private val pointLineups: PointLineupRepository,
) : StatisticsService {
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
        val events = if (identified.isEmpty()) match.events else match.events.map { event ->
            when (event) {
                is OnePlayerEvent -> event.copy(participant = effectiveActor(event.participant))
                is TwoPlayerEvent -> event.copy(
                    fromParticipant = effectiveActor(event.fromParticipant),
                    toParticipant = effectiveActor(event.toParticipant),
                )
                else -> event
            }
        }
        val intervals = announcements.list(match.id)
        return statisticsAggregatorList.fold(initialStatistics) { stats, aggregator ->
            aggregator.aggregate(stats, events, teamByParticipantId, intervals)
        }
    }
}
