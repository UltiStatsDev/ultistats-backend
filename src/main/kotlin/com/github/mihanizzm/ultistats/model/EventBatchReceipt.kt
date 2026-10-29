package com.github.mihanizzm.ultistats.model

import jakarta.persistence.*
import java.util.UUID

/** Exact successful request identity. The match lock serializes creation for a base. */
@Entity
@Table(name = "event_batch_receipts")
class EventBatchReceipt(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "match_id", nullable = false) val matchId: UUID,
    @Column(name = "base_sequence_number", nullable = false) val baseSequenceNumber: Int,
    @Column(name = "base_log_revision", nullable = false) val baseLogRevision: Long,
    @Column(nullable = false, length = 64) val fingerprint: String,
)
