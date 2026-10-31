package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpsertTeamPlayerRequest(
    @field:Schema(types = ["integer", "null"])
    val number: Int? = null,
)
