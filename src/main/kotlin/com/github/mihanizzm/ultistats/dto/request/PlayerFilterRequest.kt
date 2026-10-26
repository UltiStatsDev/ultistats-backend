package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.Parameter
import java.util.UUID

data class PlayerFilterRequest(
    @field:Parameter(description = "Фильтр по имени (частичное совпадение)")
    val name: String? = null,
    @field:Parameter(description = "Фильтр по ID команды")
    val teamId: UUID? = null,
)
