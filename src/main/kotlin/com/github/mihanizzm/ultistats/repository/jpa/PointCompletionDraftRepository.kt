package com.github.mihanizzm.ultistats.repository.jpa

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraft
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PointCompletionDraftRepository(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    fun find(matchId: UUID, pointId: UUID): StoredCompletionDraft? = jdbc.query(
        """SELECT closing_event_id, revision, base_log_revision, base_lineup_revision, insertions
            FROM match_point_completion_drafts WHERE match_id = ? AND point_id = ?""",
        { row, _ -> StoredCompletionDraft(
            row.getLong("revision"),
            PointCompletionDraft(
                row.getObject("closing_event_id", UUID::class.java), row.getLong("base_log_revision"),
                row.getObject("base_lineup_revision", Long::class.javaObjectType),
                json.readValue(row.getString("insertions")),
            ),
        ) },
        matchId, pointId,
    ).singleOrNull()

    /** Caller holds the match write lock from the revision comparison through commit. */
    fun save(matchId: UUID, pointId: UUID, stored: StoredCompletionDraft) {
        val (revision, draft) = stored
        val insertions = json.writeValueAsString(draft.insertions)
        val changed = if (revision == 1L) jdbc.update(
            """INSERT INTO match_point_completion_drafts
                (match_id, point_id, closing_event_id, revision, base_log_revision, base_lineup_revision, insertions)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
            matchId, pointId, draft.closingEventId, revision, draft.baseLogRevision, draft.baseLineupRevision, insertions,
        ) else jdbc.update(
            """UPDATE match_point_completion_drafts
                SET revision = ?, base_log_revision = ?, base_lineup_revision = ?, insertions = ?
                WHERE match_id = ? AND point_id = ? AND closing_event_id = ? AND revision = ?""",
            revision, draft.baseLogRevision, draft.baseLineupRevision, insertions,
            matchId, pointId, draft.closingEventId, revision - 1,
        )
        check(changed == 1)
    }
}

data class StoredCompletionDraft(val revision: Long, val draft: PointCompletionDraft)
