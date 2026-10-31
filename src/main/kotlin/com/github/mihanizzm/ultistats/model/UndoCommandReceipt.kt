package com.github.mihanizzm.ultistats.model

import jakarta.persistence.*
import java.util.UUID

/** Never expires or changes. Independent from append-batch base/invalidation receipts. */
@Entity
@Table(name = "undo_command_receipts", uniqueConstraints = [UniqueConstraint(columnNames = ["match_id", "command_id"])])
class UndoCommandReceipt(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "match_id", nullable = false) val matchId: UUID,
    @Column(name = "command_id", nullable = false) val commandId: UUID,
    @Column(name = "expected_last_event_id", nullable = false) val expectedLastEventId: UUID,
    @Column(name = "expected_log_revision", nullable = false) val expectedLogRevision: Long,
    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT") val resultJson: String,
)
