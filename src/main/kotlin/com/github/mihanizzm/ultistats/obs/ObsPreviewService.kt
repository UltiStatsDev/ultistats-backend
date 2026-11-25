package com.github.mihanizzm.ultistats.obs

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Authorization belongs to the staff boundary; no viewer key is issued or accepted here. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class ObsPreviewService(
    private val scoreboard: ObsScoreboardService,
    private val eventLog: ObsExactEventLogService,
    private val auto: ObsAutoSourceService,
    private val infographics: ObsInfographicsService,
) {
    fun infographics(matchId: UUID) = infographics.buildSnapshot(matchId)
    fun autoInfographics() = infographics.buildAuto()
    fun scoreboard(matchId: UUID) = scoreboard.exactSnapshot(matchId)
    fun eventLog(matchId: UUID) = eventLog.buildSnapshot(matchId)
    fun autoScoreboard() = auto.buildScoreboard()
    fun autoEventLog() = auto.buildEventLog()
}
