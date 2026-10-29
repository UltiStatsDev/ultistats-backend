package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.EventEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class EventInsertRepositoryImpl(
    private val entityManager: EntityManager,
) : EventInsertRepository {
    override fun insert(event: EventEntity): EventEntity {
        entityManager.persist(event)
        entityManager.flush()
        // Return the canonical persisted payload (PostgreSQL timestamps have microsecond precision).
        entityManager.refresh(event)
        return event
    }
}
