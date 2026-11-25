package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.TeamStatisticsResponse
import com.github.mihanizzm.ultistats.facade.StatisticsFacade
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataEventRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsInfographicsPostmatch(val statsAsOfEventAt: Instant?, val teams: List<ObsPostmatchTeam>)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsPostmatchTeam(
    val teamId: UUID,
    val score: Int?,
    val accuracy: Double?,
    val possessionTimeMs: Long?,
    val possession: Double?,
    val metrics: ObsPostmatchMetrics,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ObsPostmatchMetrics(
    val accuratePasses: Int?,
    val inaccuratePasses: Int?,
    val blocks: Long?,
    val interceptions: Int?,
    val callahans: Int?,
    val breaks: Int?,
)

@Service
class ObsInfographicsPostmatchService(
    private val statistics: StatisticsFacade,
    private val events: SpringDataEventRepository,
) {
    // Called inside the original, authorized repeatable-read infographic snapshot.
    internal fun finished(matchId: UUID, teamIds: List<UUID>): ObsInfographicsPostmatch = project(
        teamIds,
        statistics.get(matchId),
        events.findFirstByMatchIdAndDeletedAtIsNullOrderBySequenceNumberDesc(matchId)?.effectiveOccurredAt,
    )

    companion object {
        internal fun project(
            teamIds: List<UUID>,
            statistics: MatchStatisticsResponse?,
            statsAsOfEventAt: Instant?,
        ): ObsInfographicsPostmatch {
            val byTeam = statistics?.teams.orEmpty().groupBy { it.teamId }
            val ordered = teamIds.map { byTeam[it]?.singleOrNull() }
            val possessionTimes = ordered.map { it?.time?.possessionTimeMs?.takeIf { ms -> ms >= 0 } }
            // Sum as doubles to match Web Number arithmetic and avoid overflow on legacy values.
            val total = possessionTimes.filterNotNull().takeIf { it.size == ordered.size }?.sumOf(Long::toDouble)
                ?.takeIf { it > 0 }
            return ObsInfographicsPostmatch(statsAsOfEventAt, teamIds.mapIndexed { index, id ->
                val team = ordered[index]
                val complete = team?.attack?.completePasses.validCount()
                val all = team?.attack?.allPasses.validCount()
                val incomplete = if (all != null && complete != null && all >= complete) all - complete else null
                val time = possessionTimes[index]
                ObsPostmatchTeam(
                    teamId = id,
                    score = team?.attack?.score.validCount(),
                    accuracy = if (all != null && complete != null && all > 0 && complete <= all) complete.toDouble() / all else null,
                    possessionTimeMs = time,
                    possession = if (time != null && total != null) time.toDouble() / total else null,
                    metrics = team.metrics(complete, incomplete),
                )
            })
        }

        private fun Int?.validCount() = this?.takeIf { it >= 0 }

        private fun TeamStatisticsResponse?.metrics(complete: Int?, incomplete: Int?): ObsPostmatchMetrics {
            val defense = this?.defense
            val blocks = defense?.let {
                listOf(it.blocks, it.blocksAsMarker, it.blocksAsFieldPlayer)
                    .takeIf { counts -> counts.all { count -> count >= 0 } }?.sumOf(Int::toLong)
            }
            return ObsPostmatchMetrics(
                complete, incomplete, blocks, defense?.interceptions.validCount(), defense?.callahans.validCount(),
                this?.attack?.breaks.validCount(),
            )
        }
    }
}
