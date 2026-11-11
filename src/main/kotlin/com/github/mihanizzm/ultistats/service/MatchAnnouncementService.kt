package com.github.mihanizzm.ultistats.service

import com.github.mihanizzm.ultistats.exception.AnnouncementConflictCode
import com.github.mihanizzm.ultistats.exception.AnnouncementConflictException
import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.realtime.MatchRealtimePublisher
import com.github.mihanizzm.ultistats.repository.jpa.MatchAnnouncementRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchRepository
import com.github.mihanizzm.ultistats.repository.jpa.SpringDataMatchTeamRepository
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class MatchAnnouncementService(
    private val matches: SpringDataMatchRepository,
    private val teams: SpringDataMatchTeamRepository,
    private val announcements: MatchAnnouncementRepository,
    private val realtime: MatchRealtimePublisher,
    @Value("\${ultistats.announcements.write-enabled:false}") val startEnabled: Boolean,
) {
    data class StartResult(val interval: MatchAnnouncement, val created: Boolean)

    @Transactional(readOnly = true)
    fun list(matchId: UUID): List<MatchAnnouncement>? =
        matches.findByIdAndDeletedAtIsNull(matchId)?.let { announcements.list(matchId) }

    @Transactional
    fun start(matchId: UUID, id: UUID, teamId: UUID?, startedAt: Instant): MatchCommandResult<StartResult> {
        val match = matches.findByIdForUpdate(matchId) ?: return MatchCommandResult.NotFound
        val intervals = announcements.list(matchId)
        intervals.firstOrNull { it.id == id }?.let { existing ->
            if (existing.startTeamId == teamId && existing.startedAt == startedAt) {
                return MatchCommandResult.Success(StartResult(existing, created = false))
            }
            throw AnnouncementConflictException(AnnouncementConflictCode.ANNOUNCEMENT_CONFLICT, "Announcement ID already has a different start")
        }
        requireNewIntervalsEnabled()
        if (match.status != MatchStatus.IN_PROGRESS) {
            return MatchCommandResult.InvalidState(
                MatchProblem(MatchProblemCode.MATCH_NOT_IN_PROGRESS, "Match state conflict",
                    "Announcements can start only during an in-progress match", match.status),
            )
        }
        if (teamId != null && teams.findAllByMatchIdOrderByPosition(matchId).none { it.teamId == teamId }) {
            return invalid("Announcement team is not a team in this match")
        }
        if (startedAt.isBefore(requireNotNull(match.effectiveStartedAt))) {
            return invalid("Announcement cannot start before the match")
        }
        if (intervals.any { it.endedAt == null }) {
            throw AnnouncementConflictException(AnnouncementConflictCode.ANNOUNCEMENT_ALREADY_OPEN, "An announcement is already open")
        }
        if (intervals.any { it.endedAt?.isAfter(startedAt) == true }) {
            return invalid("Announcement cannot start before the previous announcement ends")
        }
        val interval = MatchAnnouncement(id, teamId, startedAt)
        announcements.insert(matchId, interval)
        realtime.matchChanged(matchId)
        return MatchCommandResult.Success(StartResult(interval, created = true))
    }

    @Transactional
    fun end(matchId: UUID, id: UUID, endedAt: Instant): MatchCommandResult<MatchAnnouncement> {
        val match = matches.findByIdForUpdate(matchId) ?: return MatchCommandResult.NotFound
        val existing = announcements.find(matchId, id) ?: return MatchCommandResult.NotFound
        if (existing.endedAt == endedAt) return MatchCommandResult.Success(existing)
        if (existing.endedAt != null) {
            throw AnnouncementConflictException(AnnouncementConflictCode.ANNOUNCEMENT_CONFLICT, "Announcement already has a different end")
        }
        if (endedAt.isBefore(existing.startedAt)) return invalid("Announcement cannot end before it starts")
        if (match.effectiveEndedAt?.isBefore(endedAt) == true) {
            return invalid("Announcement cannot end after the match")
        }
        announcements.end(matchId, id, endedAt)
        realtime.matchChanged(matchId)
        return MatchCommandResult.Success(existing.copy(endedAt = endedAt))
    }

    @Transactional
    fun correctTeam(
        matchId: UUID, id: UUID, teamId: UUID?, expectedAttributionRevision: Long,
    ): MatchCommandResult<MatchAnnouncement> {
        val match = matches.findByIdForUpdate(matchId) ?: return MatchCommandResult.NotFound
        val existing = announcements.find(matchId, id) ?: return MatchCommandResult.NotFound
        if (!startEnabled) {
            throw AnnouncementConflictException(
                AnnouncementConflictCode.ANNOUNCEMENT_WRITE_DISABLED, "Correcting announcement teams is disabled",
            )
        }
        if (existing.endedAt == null) {
            throw AnnouncementConflictException(
                AnnouncementConflictCode.ANNOUNCEMENT_NOT_CLOSED, "Only closed announcements can be corrected",
            )
        }
        if (match.status != MatchStatus.FINISHED) {
            return MatchCommandResult.InvalidState(
                MatchProblem(MatchProblemCode.MATCH_NOT_FINISHED, "Match state conflict",
                    "Announcement teams can be corrected only after the match finishes", match.status),
            )
        }
        if (existing.attributionRevision != expectedAttributionRevision) {
            throw AnnouncementConflictException(
                AnnouncementConflictCode.ANNOUNCEMENT_ATTRIBUTION_STALE,
                "Announcement attribution changed. Read the current announcement before trying again",
            )
        }
        if (teamId != null && teams.findAllByMatchIdOrderByPosition(matchId).none { it.teamId == teamId }) {
            return invalid("Announcement team is not a team in this match")
        }
        if (existing.teamId == teamId) return MatchCommandResult.Success(existing)
        announcements.correctTeam(matchId, id, teamId)
        realtime.matchChanged(matchId)
        return MatchCommandResult.Success(existing.copy(
            teamId = teamId, attributionRevision = existing.attributionRevision + 1, originalTeamId = existing.startTeamId,
        ))
    }

    /** Called only after gameplay finish validation, under the caller's match row lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun closeAtFinish(matchId: UUID, endedAt: Instant) {
        val intervals = announcements.list(matchId)
        if (intervals.any { (it.endedAt ?: it.startedAt).isAfter(endedAt) }) {
            throw AnnouncementConflictException(AnnouncementConflictCode.ANNOUNCEMENT_CONFLICT, "Match cannot finish before its announcement boundary")
        }
        intervals.singleOrNull { it.endedAt == null }?.let { announcements.end(matchId, it.id, endedAt) }
    }

    fun requireNewIntervalsEnabled() {
        if (!startEnabled) {
            throw AnnouncementConflictException(AnnouncementConflictCode.ANNOUNCEMENT_WRITE_DISABLED, "Starting announcements is disabled")
        }
    }

    private fun invalid(detail: String) =
        MatchCommandResult.InvalidRequest(MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid announcement", detail))
}
