package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.Match
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataMatchRepository : JpaRepository<Match, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Match m where m.id = :id and m.deletedAt is null")
    fun findByIdForUpdate(@Param("id") id: UUID): Match?

    fun findByIdAndDeletedAtIsNull(id: UUID): Match?
    fun findAllByStartedAtIsNotNull(): List<Match>
    fun findAllByDeletedAtIsNull(): List<Match>
    fun countByDeletedAtIsNull(): Long
}
