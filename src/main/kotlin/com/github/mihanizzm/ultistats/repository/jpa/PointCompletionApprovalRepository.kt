package com.github.mihanizzm.ultistats.repository.jpa

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PointCompletionApprovalRepository(private val jdbc: JdbcTemplate) {
    fun exists(matchId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM match_point_completion_approvals WHERE match_id = ?)", Boolean::class.java, matchId,
    ) == true

    fun findAll(matchId: UUID): List<StoredPointApproval> = jdbc.query(
        "SELECT point_id, closing_event_id, insertions FROM match_point_completion_approvals WHERE match_id = ?",
        { row, _ -> StoredPointApproval(
            row.getObject("point_id", UUID::class.java),
            row.getObject("closing_event_id", UUID::class.java),
            row.getString("insertions"),
        ) },
        matchId,
    )
}

data class StoredPointApproval(val pointId: UUID, val closingEventId: UUID, val insertionPayload: String)
