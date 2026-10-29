package com.github.mihanizzm.ultistats.repository.jpa

import com.github.mihanizzm.ultistats.dto.common.SortDirection
import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import com.github.mihanizzm.ultistats.dto.request.PlayerFilterRequest
import com.github.mihanizzm.ultistats.dto.request.TeamFilterRequest
import com.github.mihanizzm.ultistats.model.Match
import com.github.mihanizzm.ultistats.model.Player
import com.github.mihanizzm.ultistats.model.Team
import com.github.mihanizzm.ultistats.repository.ListPage
import com.github.mihanizzm.ultistats.repository.ListPageRequest
import jakarta.persistence.EntityManager
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** SQL owns filtering, ordering, pagination and count; only the selected rows become entities. */
@Repository
@Transactional(readOnly = true)
class ListQueryRepository(private val em: EntityManager) {
    private val postgres: Boolean
        get() = em.entityManagerFactory.unwrap(SessionFactoryImplementor::class.java)
            .jdbcServices.dialect is PostgreSQLDialect

    fun players(filter: PlayerFilterRequest, request: ListPageRequest): ListPage<Player> =
        page(Player::class.java, "players", playerFilter(filter), request,
            mapOf("firstName" to textOrder("e.first_name"), "lastName" to textOrder("e.last_name")))

    fun teams(filter: TeamFilterRequest, request: ListPageRequest): ListPage<Team> =
        page(Team::class.java, "teams", teamFilter(filter), request, mapOf("name" to textOrder("e.name")))

    fun matches(filter: MatchFilterRequest, request: ListPageRequest): ListPage<Match> =
        page(Match::class.java, "matches", matchFilter(filter), request, mapOf(
            "plannedStartTimestamp" to "e.planned_start_timestamp", "startedAt" to "e.started_at",
            "endedAt" to "e.ended_at", "status" to statusExpression,
        ))

    fun countPlayers(filter: PlayerFilterRequest): Long = count("players", playerFilter(filter))
    fun countTeams(filter: TeamFilterRequest): Long = count("teams", teamFilter(filter))
    fun countMatches(filter: MatchFilterRequest): Long = count("matches", matchFilter(filter))

    private class Filter {
        val clauses = mutableListOf("e.deleted_at IS NULL")
        val parameters = linkedMapOf<String, Any>()
        fun add(clause: String, name: String, value: Any?) {
            if (value != null) {
                clauses += clause
                parameters[name] = value
            }
        }
        val sql: String get() = clauses.joinToString(" AND ")
    }

    private fun playerFilter(filter: PlayerFilterRequest) = Filter().apply {
        add("LOWER(CONCAT(e.first_name, ' ', e.last_name)) LIKE LOWER(CONCAT('%', :name, '%')) ESCAPE ''", "name", filter.name ?: "")
        add("EXISTS (SELECT 1 FROM team_players tp WHERE tp.player_id = e.id AND tp.team_id = :teamId AND tp.deleted_at IS NULL)", "teamId", filter.teamId)
    }

    private fun teamFilter(filter: TeamFilterRequest) = Filter().apply {
        add("LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%')) ESCAPE ''", "name", filter.name)
    }

    private fun matchFilter(filter: MatchFilterRequest) = Filter().apply {
        add("EXISTS (SELECT 1 FROM match_teams mt WHERE mt.match_id = e.id AND mt.team_id = :teamId)", "teamId", filter.teamId)
        add("($statusExpression) = :status", "status", filter.status?.name)
        add("e.planned_start_timestamp >= :dateFrom", "dateFrom", filter.dateFrom)
        add("e.planned_start_timestamp <= :dateTo", "dateTo", filter.dateTo)
    }

    private fun <T> page(type: Class<T>, table: String, filter: Filter, request: ListPageRequest, fields: Map<String, String>): ListPage<T> {
        val field = requireNotNull(fields[request.sort.field]) { "Unsupported sort field: ${request.sort.field}" }
        val direction = if (request.sort.direction == SortDirection.ASC) "ASC NULLS LAST" else "DESC NULLS FIRST"
        // UUID.compareTo compares both 64-bit halves as signed longs. Database UUID ordering is unsigned.
        val id = "CAST(e.id AS VARCHAR)"
        val tieBreak = "CASE WHEN SUBSTRING($id, 1, 1) >= '8' THEN 0 ELSE 1 END, " +
            "SUBSTRING($id, 1, 18), CASE WHEN SUBSTRING($id, 20, 1) >= '8' THEN 0 ELSE 1 END, SUBSTRING($id, 20)"
        val query = em.createNativeQuery(
            "SELECT e.* FROM $table e WHERE ${filter.sql} ORDER BY $field $direction, $tieBreak LIMIT :pageSize OFFSET :pageOffset", type,
        )
        filter.parameters.forEach { (name, value) -> query.setParameter(name, value) }
        query.setParameter("pageSize", request.size)
        query.setParameter("pageOffset", request.offset)
        @Suppress("UNCHECKED_CAST")
        val content = query.resultList as List<T>
        return ListPage(content, count(table, filter))
    }

    private fun count(table: String, filter: Filter): Long {
        val query = em.createNativeQuery("SELECT COUNT(*) FROM $table e WHERE ${filter.sql}")
        filter.parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return (query.singleResult as Number).toLong()
    }

    /** PostgreSQL collations compare characters, while Kotlin compares UTF-16 code units. */
    private fun textOrder(column: String): String = if (!postgres) column else """
        ARRAY(SELECT unit FROM regexp_split_to_table($column, '') WITH ORDINALITY AS chars(ch, pos)
        CROSS JOIN LATERAL unnest(CASE WHEN ascii(ch) > 65535
            THEN ARRAY[55296 + (ascii(ch) - 65536) / 1024, 56320 + (ascii(ch) - 65536) % 1024]
            ELSE ARRAY[ascii(ch)] END) WITH ORDINALITY AS units(unit, part)
        ORDER BY pos, part)
    """.trimIndent()

    private companion object {
        const val statusExpression = "CASE WHEN e.ended_at IS NOT NULL THEN 'FINISHED' WHEN e.started_at IS NOT NULL THEN 'IN_PROGRESS' ELSE 'PLANNED' END"
    }
}
