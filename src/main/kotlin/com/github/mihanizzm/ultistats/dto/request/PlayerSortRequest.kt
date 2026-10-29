package com.github.mihanizzm.ultistats.dto.request

import com.github.mihanizzm.ultistats.dto.common.SortDirection
import com.github.mihanizzm.ultistats.dto.common.SortField
import com.github.mihanizzm.ultistats.dto.common.resolveSort
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema

enum class PlayerSortField(override val field: String) : SortField {
    LAST_NAME("lastName"),
    FIRST_NAME("firstName"),
}

data class PlayerSortRequest(
    @field:Parameter(
        description = "Поле сортировки. По умолчанию LAST_NAME.",
        schema = Schema(implementation = PlayerSortField::class, defaultValue = "LAST_NAME"),
    )
    val sortBy: PlayerSortField? = null,
    @field:Parameter(
        description = "Направление сортировки. По умолчанию ASC.",
        schema = Schema(implementation = SortDirection::class, defaultValue = "ASC"),
    )
    val sortDirection: SortDirection? = null,
    @field:Parameter(
        description = "Устаревший формат field:direction. Поля: lastName, firstName. Нельзя совмещать с sortBy/sortDirection.",
        example = "lastName:asc", deprecated = true,
    )
    val sort: String? = null,
) {
    fun toSortParam(parameters: Map<String, Array<String>>) =
        resolveSort(sortBy, sortDirection, sort, PlayerSortField.LAST_NAME, PlayerSortField.entries, parameters)
}
