package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.EventEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataEventRepository : JpaRepository<EventEntity, UUID>, EventInsertRepository {
    fun findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId: UUID): List<EventEntity>
    fun findFirstByMatchIdOrderBySequenceNumberDesc(matchId: UUID): EventEntity?
    fun findFirstByMatchIdAndDeletedAtIsNullOrderBySequenceNumberDesc(matchId: UUID): EventEntity?
    fun findAllByMatchIdAndDeletedAtIsNull(matchId: UUID): List<EventEntity>
    fun findByIdAndMatchIdAndDeletedAtIsNull(id: UUID, matchId: UUID): EventEntity?
    fun countByMatchIdAndDeletedAtIsNull(matchId: UUID): Long
}
