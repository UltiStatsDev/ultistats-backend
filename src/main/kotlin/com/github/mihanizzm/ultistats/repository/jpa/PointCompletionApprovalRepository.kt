package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.dto.response.PointCompletionApprovalResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PointCompletionApprovalRepository(private val jdbc: JdbcTemplate) {
    fun exists(matchId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM match_point_completion_approvals WHERE match_id = ?)", Boolean::class.java, matchId,
    ) == true

    fun exists(matchId: UUID, pointId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM match_point_completion_approvals WHERE match_id = ? AND point_id = ?)",
        Boolean::class.java, matchId, pointId,
    ) == true

    internal fun pointIds(matchId: UUID): Set<UUID> = jdbc.query(
        "SELECT point_id FROM match_point_completion_approvals WHERE match_id = ?",
        { row, _ -> row.getObject("point_id", UUID::class.java) }, matchId,
    ).toSet()

    internal fun findReceipt(matchId: UUID, operationId: UUID): ApprovalReceipt? = jdbc.query(
        """SELECT r.*, a.closing_event_id FROM match_point_completion_approval_receipts r
           JOIN match_point_completion_approvals a ON a.match_id = r.match_id AND a.point_id = r.point_id
           WHERE r.match_id = ? AND r.operation_id = ?""",
        { row, _ -> ApprovalReceipt(
            row.getLong("expected_log_revision"), row.getObject("expected_lineup_revision", Long::class.javaObjectType),
            PointCompletionApprovalResponse(operationId, row.getObject("point_id", UUID::class.java),
                row.getObject("closing_event_id", UUID::class.java), row.getLong("draft_revision"),
                row.getLong("log_revision"), row.getInt("last_sequence_number"), replayed = true),
        ) },
        matchId, operationId,
    ).singleOrNull()

    internal fun insertReceipt(matchId: UUID, receipt: ApprovalReceipt) {
        val result = receipt.response
        jdbc.update(
            """INSERT INTO match_point_completion_approval_receipts
               (match_id, point_id, operation_id, expected_log_revision, expected_lineup_revision,
               draft_revision, log_revision, last_sequence_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            matchId, result.pointId, result.operationId, receipt.expectedLogRevision, receipt.expectedLineupRevision,
            result.draftRevision, result.logRevision, result.lastSequenceNumber,
        )
    }

    internal fun insert(matchId: UUID, approval: StoredPointApproval) {
        jdbc.update(
            """INSERT INTO match_point_completion_approvals (match_id, point_id, closing_event_id, insertions)
               VALUES (?, ?, ?, ?)""",
            matchId, approval.pointId, approval.closingEventId, approval.insertionPayload,
        )
    }

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

internal data class ApprovalReceipt(
    val expectedLogRevision: Long,
    val expectedLineupRevision: Long?,
    val response: PointCompletionApprovalResponse,
)
