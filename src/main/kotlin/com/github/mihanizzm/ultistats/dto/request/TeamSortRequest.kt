package com.github.mihanizzm.ultistats.dto.request

import com.github.mihanizzm.ultistats.dto.common.SortDirection
import com.github.mihanizzm.ultistats.dto.common.SortField
import com.github.mihanizzm.ultistats.dto.common.resolveSort
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema

enum class TeamSortField(override val field: String) : SortField {
    NAME("name"),
}

data class TeamSortRequest(
    @field:Parameter(
        description = "Поле сортировки. По умолчанию NAME.",
        schema = Schema(implementation = TeamSortField::class, defaultValue = "NAME"),
    )
    val sortBy: TeamSortField? = null,
    @field:Parameter(
        description = "Направление сортировки. По умолчанию ASC.",
        schema = Schema(implementation = SortDirection::class, defaultValue = "ASC"),
    )
    val sortDirection: SortDirection? = null,
    @field:Parameter(
        description = "Устаревший формат field:direction. Поля: name. Нельзя совмещать с sortBy/sortDirection.",
        example = "name:asc", deprecated = true,
    )
    val sort: String? = null,
) {
    fun toSortParam(parameters: Map<String, Array<String>>) =
        resolveSort(sortBy, sortDirection, sort, TeamSortField.NAME, TeamSortField.entries, parameters)
}
