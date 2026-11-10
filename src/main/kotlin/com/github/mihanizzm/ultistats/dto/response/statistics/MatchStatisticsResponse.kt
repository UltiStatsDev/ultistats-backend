package com.github.mihanizzm.ultistats.dto.response.statistics

import java.util.UUID

data class MatchStatisticsResponse(
    val matchId: UUID,
    val teams: List<TeamStatisticsResponse>,
    val time: MatchTimeStatisticsResponse,
    val announcements: AnnouncementSummaryResponse,
)

data class AnnouncementSummaryResponse(
    val count: Int,
    val durationMs: Long,
    val byTeam: List<TeamAnnouncementTotalsResponse>,
    val unknown: AnnouncementTotalsResponse,
)

data class TeamAnnouncementTotalsResponse(
    val teamId: UUID,
    val count: Int,
    val durationMs: Long,
)

data class AnnouncementTotalsResponse(
    val count: Int,
    val durationMs: Long,
)
