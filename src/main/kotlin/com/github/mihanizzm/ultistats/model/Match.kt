package com.github.mihanizzm.ultistats.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.mihanizzm.ultistats.model.events.Event
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "matches")
/**
 * The persisted match row plus transient aggregate fields used by the existing API.
 * Team, roster, score, and event data live in normalized tables and are populated by
 * MatchServiceImpl when it builds a read model.
 */
data class Match(
    @Id
    val id: UUID,

    @Transient
    val teamIds: List<UUID>,

    @Transient
    val events: MutableList<Event> = mutableListOf(),

    @Transient
    val eventCount: Int = events.size,

    @Transient
    val teamScores: MutableList<TeamScore> = mutableListOf(),

    @Transient
    val participantsByTeam: Map<UUID, List<MatchParticipant>> = emptyMap(),

    @Transient
    val teamNamesById: Map<UUID, String> = emptyMap(),

    @Column(name = "planned_start_timestamp")
    val plannedStartTimestamp: Instant? = null,

    @Column(name = "players_per_team", nullable = false)
    val playersPerTeam: Int = DEFAULT_PLAYERS_PER_TEAM,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "ended_at")
    var endedAt: Instant? = null,

    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
) {
    init {
        require(playersPerTeam >= MIN_PLAYERS_PER_TEAM) {
            "Players per team must be at least $MIN_PLAYERS_PER_TEAM"
        }
    }

    @get:JsonIgnore
    val status: MatchStatus
        get() = when {
            endedAt != null -> MatchStatus.FINISHED
            startedAt != null -> MatchStatus.IN_PROGRESS
            else -> MatchStatus.PLANNED
        }

    companion object {
        const val MIN_PLAYERS_PER_TEAM = 2
        const val DEFAULT_PLAYERS_PER_TEAM = 7
    }

}
