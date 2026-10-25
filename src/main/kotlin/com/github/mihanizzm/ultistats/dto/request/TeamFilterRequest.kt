package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.Parameter

data class TeamFilterRequest(
    @field:Parameter(description = "Фильтр по названию (частичное совпадение)")
    val name: String? = null,
)
