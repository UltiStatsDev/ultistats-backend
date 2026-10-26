package com.github.mihanizzm.ultistats.dto.request

import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.validation.query.ValidDateRange
import io.swagger.v3.oas.annotations.Parameter
import java.time.Instant
import java.util.UUID

@ValidDateRange
data class MatchFilterRequest(
    @field:Parameter(description = "Фильтр по ID команды-участника")
    val teamId: UUID? = null,
    @field:Parameter(description = "Фильтр по статусу матча")
    val status: MatchStatus? = null,
    @field:Parameter(description = "Плановое начало матча: от (ISO 8601, включительно). Не позже dateTo.")
    val dateFrom: Instant? = null,
    @field:Parameter(description = "Плановое начало матча: до (ISO 8601, включительно). Не раньше dateFrom.")
    val dateTo: Instant? = null,
)
