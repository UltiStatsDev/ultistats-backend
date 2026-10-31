package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class CreateTeamRequest(
    val name: String,
    @field:Schema(types = ["string", "null"])
    val city: String? = null,
)
