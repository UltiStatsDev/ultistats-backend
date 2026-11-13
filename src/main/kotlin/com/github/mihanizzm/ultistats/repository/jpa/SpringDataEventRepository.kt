package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.EventEntity
import com.github.mihanizzm.ultistats.model.events.EventType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface SpringDataEventRepository : JpaRepository<EventEntity, UUID>, EventInsertRepository {
    fun findAllByMatchIdAndDeletedAtIsNullOrderBySequenceNumber(matchId: UUID): List<EventEntity>
    @Query("""
        SELECT e FROM EventEntity e
        WHERE e.matchId = :matchId AND e.deletedAt > :after AND e.deletedAt <= :through
        ORDER BY e.deletedAt, e.sequenceNumber
    """)
    fun findRetractions(matchId: UUID, after: Instant, through: Instant): List<EventEntity>
    fun findFirstByMatchIdOrderBySequenceNumberDesc(matchId: UUID): EventEntity?
    fun findFirstByMatchIdAndDeletedAtIsNullOrderBySequenceNumberDesc(matchId: UUID): EventEntity?
    fun findAllByMatchIdAndDeletedAtIsNull(matchId: UUID): List<EventEntity>
    fun findByIdAndMatchIdAndDeletedAtIsNull(id: UUID, matchId: UUID): EventEntity?
    fun countByMatchIdAndDeletedAtIsNull(matchId: UUID): Long
    fun existsByMatchIdAndEventTypeAndDeletedAtIsNull(matchId: UUID, eventType: EventType): Boolean
}
