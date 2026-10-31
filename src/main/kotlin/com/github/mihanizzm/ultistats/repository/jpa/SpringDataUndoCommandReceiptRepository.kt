package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.UndoCommandReceipt
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataUndoCommandReceiptRepository : JpaRepository<UndoCommandReceipt, UUID> {
    fun findByMatchIdAndCommandId(matchId: UUID, commandId: UUID): UndoCommandReceipt?
}
