package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema

/**
 * DTO для частичного обновления игрока.
 * Все поля необязательны — обновляются только переданные поля.
 */
data class UpdatePlayerRequest(
    @field:Schema(types = ["string", "null"])
    val firstName: String? = null,
    @field:Schema(types = ["string", "null"])
    val lastName: String? = null,
)
