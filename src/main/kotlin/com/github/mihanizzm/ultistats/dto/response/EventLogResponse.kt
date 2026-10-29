package com.github.mihanizzm.ultistats.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Consistent canonical log snapshot, or confirmed batch events with the current log position.")
data class EventLogResponse(
    val events: List<EventResponse>,
    @field:Schema(description = "Highest ever allocated sequence number, including deleted events; zero for an empty history.")
    val lastSequenceNumber: Int,
    @field:Schema(description = "Monotonic revision; increases once per append command, PATCH or DELETE. Retry does not increase it.")
    val logRevision: Long,
)
