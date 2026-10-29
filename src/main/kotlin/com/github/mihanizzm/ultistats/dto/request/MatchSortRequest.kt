package com.github.mihanizzm.ultistats.dto.request

import com.github.mihanizzm.ultistats.dto.common.SortDirection
import com.github.mihanizzm.ultistats.dto.common.SortField
import com.github.mihanizzm.ultistats.dto.common.resolveSort
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema

enum class MatchSortField(override val field: String) : SortField {
    PLANNED_START_TIMESTAMP("plannedStartTimestamp"),
    STARTED_AT("startedAt"),
    ENDED_AT("endedAt"),
    STATUS("status"),
}

data class MatchSortRequest(
    @field:Parameter(
        description = "Поле сортировки. По умолчанию PLANNED_START_TIMESTAMP.",
        schema = Schema(implementation = MatchSortField::class, defaultValue = "PLANNED_START_TIMESTAMP"),
    )
    val sortBy: MatchSortField? = null,
    @field:Parameter(
        description = "Направление сортировки. По умолчанию ASC.",
        schema = Schema(implementation = SortDirection::class, defaultValue = "ASC"),
    )
    val sortDirection: SortDirection? = null,
    @field:Parameter(
        description = "Устаревший формат field:direction. Поля: plannedStartTimestamp, startedAt, endedAt, status. " +
            "Нельзя совмещать с sortBy/sortDirection.",
        example = "plannedStartTimestamp:asc", deprecated = true,
    )
    val sort: String? = null,
) {
    fun toSortParam(parameters: Map<String, Array<String>>) = resolveSort(
        sortBy, sortDirection, sort, MatchSortField.PLANNED_START_TIMESTAMP, MatchSortField.entries, parameters,
    )
}
