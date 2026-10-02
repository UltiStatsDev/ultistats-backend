package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.model.Player
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SpringDataPlayerRepository : JpaRepository<Player, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Player p where p.id = :id and p.deletedAt is null")
    fun findByIdForUpdate(@Param("id") id: UUID): Player?

    fun findByIdAndDeletedAtIsNull(id: UUID): Player?

    fun findAllByIdInAndDeletedAtIsNull(ids: List<UUID>): List<Player>

    fun findAllByDeletedAtIsNull(): List<Player>

    @Query("""
        SELECT p FROM Player p
        WHERE p.deletedAt IS NULL
        AND LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE LOWER(CONCAT('%', :name, '%'))
    """)
    fun findFiltered(name: String): List<Player>

    @Query("""
        SELECT COUNT(p) FROM Player p
        WHERE p.deletedAt IS NULL
        AND LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE LOWER(CONCAT('%', :name, '%'))
    """)
    fun countFiltered(name: String): Long

    fun countByDeletedAtIsNull(): Long
}
