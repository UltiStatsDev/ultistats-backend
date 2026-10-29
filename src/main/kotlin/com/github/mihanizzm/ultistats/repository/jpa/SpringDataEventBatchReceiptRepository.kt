package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.EventBatchReceipt
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataEventBatchReceiptRepository : JpaRepository<EventBatchReceipt, UUID> {
    fun findByMatchIdAndBaseSequenceNumberAndBaseLogRevision(
        matchId: UUID, baseSequenceNumber: Int, baseLogRevision: Long,
    ): EventBatchReceipt?
}
