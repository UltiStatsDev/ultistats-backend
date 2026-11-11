package com.github.mihanizzm.ultistats.service.statistics

import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.model.statistics.PlayerStatistics
import com.github.mihanizzm.ultistats.model.statistics.TeamStatistics
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Агрегатор для инкрементального подсчёта статистики по событиям.
 * Консолидирует логику из всех StatAffectingEvent классов.
 */
@Component
class IncrementalStatisticsAggregator : StatisticsAggregator {

    override fun aggregate(
        previousStatisticsState: MatchStatistics,
        events: List<Event>,
        teamByParticipantId: Map<UUID, UUID>,
        announcements: List<MatchAnnouncement>,
    ): MatchStatistics {
        var stats = previousStatisticsState
        for (event in events) {
            stats = applyEvent(stats, event, teamByParticipantId)
        }
        return stats
    }

    private fun applyEvent(
        stats: MatchStatistics,
        event: Event,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        return when (event.type) {
            EventType.PASS -> applyPass(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.GOAL -> applyGoal(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.INCOMPLETE_PASS -> applyIncompletePass(stats, event as OnePlayerEvent, teamByParticipantId)
            EventType.PULL -> applyPull(stats, event as OnePlayerEvent, teamByParticipantId)
            EventType.BRICK -> applyBrick(stats, event as OnePlayerEvent, teamByParticipantId)
            EventType.PICKUP -> applyPickup(stats, event as OnePlayerEvent, teamByParticipantId)
            EventType.BLOCK -> applyBlock(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.BLOCK_MARKER -> applyBlockMarker(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.BLOCK_FIELD -> applyBlockField(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.INTERCEPTION -> applyInterception(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.CALLAHAN -> applyCallahan(stats, event as TwoPlayerEvent, teamByParticipantId)
            EventType.TIMEOUT_START, EventType.TIMEOUT_END,
            EventType.HALFTIME_START, EventType.HALFTIME_END,
            EventType.ANNOUNCEMENT_START, EventType.ANNOUNCEMENT_END, EventType.POSSESSION_SET -> stats
        }
    }

    private fun applyPass(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(passes = stat.attack.passes + 1)
                )
                event.toParticipant -> stat.copy(
                    attack = stat.attack.copy(catches = stat.attack.catches + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(
                        allPasses = stat.attack.allPasses + 1,
                        completePasses = stat.attack.completePasses + 1,
                    )
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyGoal(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(
                        assists = stat.attack.assists + 1,
                        passes = stat.attack.passes + 1,
                    )
                )
                event.toParticipant -> stat.copy(
                    attack = stat.attack.copy(
                        catches = stat.attack.catches + 1,
                        goals = stat.attack.goals + 1,
                    )
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(
                        allPasses = stat.attack.allPasses + 1,
                        completePasses = stat.attack.completePasses + 1,
                        score = stat.attack.score + 1,
                    )
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyIncompletePass(
        stats: MatchStatistics,
        event: OnePlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.participant -> stat.copy(
                    attack = stat.attack.copy(incompletePasses = stat.attack.incompletePasses + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.participant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyPull(
        stats: MatchStatistics,
        event: OnePlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.participant -> stat.copy(
                    attack = stat.attack.copy(pulls = stat.attack.pulls + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.participant) -> stat.copy(
                    attack = stat.attack.copy(pulls = stat.attack.pulls + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyBrick(
        stats: MatchStatistics,
        event: OnePlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.participant -> stat.copy(
                    attack = stat.attack.copy(bricks = stat.attack.bricks + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.participant) -> stat.copy(
                    attack = stat.attack.copy(bricks = stat.attack.bricks + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyPickup(
        stats: MatchStatistics,
        event: OnePlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.participant -> stat.copy(
                    attack = stat.attack.copy(discPossessions = stat.attack.discPossessions + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.participant) -> stat.copy(
                    attack = stat.attack.copy(possessions = stat.attack.possessions + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyBlock(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            if (stat.participantId == event.toParticipant) {
                stat.copy(defense = stat.defense.copy(blocks = stat.defense.blocks + 1))
            } else {
                stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1),
                )
                teamByParticipantId.getValue(event.toParticipant) -> stat.copy(
                    defense = stat.defense.copy(blocks = stat.defense.blocks + 1),
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyBlockMarker(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(dropsOnMarker = stat.attack.dropsOnMarker + 1)
                )
                event.toParticipant -> stat.copy(
                    defense = stat.defense.copy(blocksAsMarker = stat.defense.blocksAsMarker + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1)
                )
                teamByParticipantId.getValue(event.toParticipant) -> stat.copy(
                    defense = stat.defense.copy(blocksAsMarker = stat.defense.blocksAsMarker + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyBlockField(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(dropsOnField = stat.attack.dropsOnField + 1)
                )
                event.toParticipant -> stat.copy(
                    defense = stat.defense.copy(blocksAsFieldPlayer = stat.defense.blocksAsFieldPlayer + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1)
                )
                teamByParticipantId.getValue(event.toParticipant) -> stat.copy(
                    defense = stat.defense.copy(blocksAsFieldPlayer = stat.defense.blocksAsFieldPlayer + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyInterception(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(dropsOnField = stat.attack.dropsOnField + 1)
                )
                event.toParticipant -> stat.copy(
                    defense = stat.defense.copy(interceptions = stat.defense.interceptions + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1)
                )
                teamByParticipantId.getValue(event.toParticipant) -> stat.copy(
                    defense = stat.defense.copy(interceptions = stat.defense.interceptions + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }

    private fun applyCallahan(
        stats: MatchStatistics,
        event: TwoPlayerEvent,
        teamByParticipantId: Map<UUID, UUID>,
    ): MatchStatistics {
        val newPlayerStats = stats.playerStatistics.map { stat ->
            when (stat.participantId) {
                event.fromParticipant -> stat.copy(
                    attack = stat.attack.copy(callahanDrops = stat.attack.callahanDrops + 1)
                )
                event.toParticipant -> stat.copy(
                    defense = stat.defense.copy(callahans = stat.defense.callahans + 1),
                    attack = stat.attack.copy(goals = stat.attack.goals + 1)
                )
                else -> stat
            }
        }

        val newTeamStats = stats.teamStatistics.map { stat ->
            when (stat.teamId) {
                teamByParticipantId.getValue(event.fromParticipant) -> stat.copy(
                    attack = stat.attack.copy(allPasses = stat.attack.allPasses + 1)
                )
                teamByParticipantId.getValue(event.toParticipant) -> stat.copy(
                    defense = stat.defense.copy(callahans = stat.defense.callahans + 1),
                    attack = stat.attack.copy(score = stat.attack.score + 1)
                )
                else -> stat
            }
        }

        return stats.copy(
            playerStatistics = newPlayerStats,
            teamStatistics = newTeamStats,
        )
    }
}
