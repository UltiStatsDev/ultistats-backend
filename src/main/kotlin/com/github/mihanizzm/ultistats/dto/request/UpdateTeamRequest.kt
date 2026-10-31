package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema

/**
 * DTO для частичного обновления команды.
 * Все поля необязательны — обновляются только переданные поля.
 */
data class UpdateTeamRequest(
    @field:Schema(types = ["string", "null"])
    val name: String? = null,
    @field:Schema(types = ["string", "null"])
    val city: String? = null,
)
