package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.Team
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataTeamRepository : JpaRepository<Team, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :id and t.deletedAt is null")
    fun findByIdForUpdate(@Param("id") id: UUID): Team?

    fun findByIdAndDeletedAtIsNull(id: UUID): Team?

    fun findAllByDeletedAtIsNull(): List<Team>

    @Query("SELECT t FROM Team t WHERE t.deletedAt IS NULL AND LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun findByNameContainingIgnoreCase(name: String): List<Team>

    @Query("SELECT COUNT(t) FROM Team t WHERE t.deletedAt IS NULL AND LOWER(t.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun countByNameContainingIgnoreCase(name: String): Long

    fun findAllByIdInAndDeletedAtIsNull(ids: List<UUID>): List<Team>

    fun countByDeletedAtIsNull(): Long
}
