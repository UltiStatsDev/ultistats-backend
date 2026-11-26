package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.dto.response.statistics.ParticipantStatisticsResponse
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import java.util.UUID

data class ObsLeaderComponents(
    val ordinaryAccuratePasses: Long, val ratedGoals: Long, val assists: Long,
    val blocks: Long, val blocksAsMarker: Long, val blocksAsFieldPlayer: Long,
    val interceptions: Long, val callahans: Long, val attackTenths: Long, val defenseTenths: Long,
)
data class ObsLeader(
    val participantId: UUID, val name: String, val number: Int?, val ratingTenths: Long,
    val components: ObsLeaderComponents, val photoUrl: String? = null,
)
enum class ObsLeadersState { READY, UNAVAILABLE }
data class ObsTeamLeaders(val teamId: UUID, val name: String, val state: ObsLeadersState, val attack: List<ObsLeader>, val defense: List<ObsLeader>)

/** Only a presentation of ready canonical counters; no journal replay or public-stat mutation. */
object ObsInfographicsRatings {
    fun rate(player: ParticipantStatisticsResponse): ObsLeaderComponents? {
        val a = player.attack
        val d = player.defense
        val passes = a.passes.toLong() - a.assists
        val goals = a.goals.toLong() - d.callahans
        if (listOf(a.passes, a.assists, a.goals, d.blocks, d.blocksAsMarker, d.blocksAsFieldPlayer,
                d.interceptions, d.callahans).any { it < 0 } || passes < 0 || goals < 0) return null
        // Every operand starts as Int; promotion precedes arithmetic, so all sums fit Long.
        val blocks = d.blocks.toLong() + d.blocksAsMarker + d.blocksAsFieldPlayer
        return ObsLeaderComponents(passes, goals, a.assists.toLong(), d.blocks.toLong(), d.blocksAsMarker.toLong(),
            d.blocksAsFieldPlayer.toLong(), d.interceptions.toLong(), d.callahans.toLong(),
            15L * goals + 15L * a.assists + passes, 15L * blocks + 20L * d.interceptions + 75L * d.callahans)
    }

    fun team(teamId: UUID, name: String, players: List<ParticipantStatisticsResponse>): ObsTeamLeaders {
        val rated = players.filter { it.kind == MatchParticipantKind.PLAYER }.map { it to rate(it) }
        if (rated.any { it.second == null }) return ObsTeamLeaders(teamId, name, ObsLeadersState.UNAVAILABLE, emptyList(), emptyList())
        fun leaders(attack: Boolean): List<ObsLeader> {
            fun score(c: ObsLeaderComponents) = if (attack) c.attackTenths else c.defenseTenths
            val maximum = rated.maxOfOrNull { score(it.second!!) } ?: 0L
            return rated.filter { maximum > 0 && score(it.second!!) == maximum }
                .sortedBy { it.first.participantId.toString() }.map { (player, c) ->
                    ObsLeader(player.participantId, player.displayName, player.number, maximum, c!!)
                }
        }
        return ObsTeamLeaders(teamId, name, ObsLeadersState.READY, leaders(true), leaders(false))
    }
}
