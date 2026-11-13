package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.export.StatisticsZipExporter
import com.github.mihanizzm.ultistats.mapper.StatisticsResponseMapper
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.statistics.StatisticsService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class StatisticsFacade(
    private val matchService: MatchService,
    private val statisticsService: StatisticsService,
    private val responseMapper: StatisticsResponseMapper,
    private val zipExporter: StatisticsZipExporter,
) {
    fun get(matchId: UUID): MatchStatisticsResponse? {
        val match = matchService.get(matchId) ?: return null
        return responseMapper.toResponse(
            match,
            statisticsService.recalculateMatchStatistics(match),
        )
    }

    fun export(matchId: UUID): ByteArray? = get(matchId)?.let(zipExporter::export)
}
