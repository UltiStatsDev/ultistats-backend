package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class PaginationRequest(
    @field:Min(0)
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @field:Parameter(description = "Номер страницы (начиная с 0)", schema = Schema(implementation = Int::class, defaultValue = "0", minimum = "0"))
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @field:Parameter(description = "Размер страницы (1–100)", schema = Schema(implementation = Int::class, defaultValue = "20", minimum = "1", maximum = "100"))
    val size: Int = 20,
)
