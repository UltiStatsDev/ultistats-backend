package com.github.mihanizzm.ultistats.facade

import com.github.mihanizzm.ultistats.dto.response.statistics.AnnouncementSummaryResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.AnnouncementTotalsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchStatisticsResponse
import com.github.mihanizzm.ultistats.dto.response.statistics.MatchTimeStatisticsResponse
import com.github.mihanizzm.ultistats.export.StatisticsZipExporter
import com.github.mihanizzm.ultistats.mapper.StatisticsResponseMapper
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.statistics.MatchStatistics
import com.github.mihanizzm.ultistats.service.MatchService
import com.github.mihanizzm.ultistats.service.statistics.StatisticsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class StatisticsFacadeTest {
    private val matchService = mock(MatchService::class.java)
    private val statisticsService = mock(StatisticsService::class.java)
    private val responseMapper = mock(StatisticsResponseMapper::class.java)
    private val facade = StatisticsFacade(matchService, statisticsService, responseMapper, StatisticsZipExporter())

    @Test
    fun `loads match once and propagates the same instance through calculation and mapping`() {
        val match = Match(id = UUID.randomUUID(), teamIds = emptyList())
        val statistics = MatchStatistics(playerStatistics = emptyList(), teamStatistics = emptyList())
        val response = MatchStatisticsResponse(
            announcements = AnnouncementSummaryResponse(0, 0, emptyList(), AnnouncementTotalsResponse(0, 0)),
            matchId = match.id,
            teams = emptyList(),
            time = MatchTimeStatisticsResponse(0, 0, 0, 0, 0),
        )
        `when`(matchService.get(match.id)).thenReturn(match)
        `when`(statisticsService.recalculateMatchStatistics(same(match) ?: match)).thenReturn(statistics)
        `when`(responseMapper.toResponse(same(match) ?: match, same(statistics) ?: statistics)).thenReturn(response)

        assertThat(facade.get(match.id)).isSameAs(response)

        verify(matchService, times(1)).get(match.id)
        verify(statisticsService, times(1)).recalculateMatchStatistics(same(match) ?: match)
        verify(responseMapper, times(1)).toResponse(same(match) ?: match, same(statistics) ?: statistics)
        verifyNoMoreInteractions(matchService, statisticsService, responseMapper)
    }
}
