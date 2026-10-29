package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Atomic ordered append. Every event requires a stable client eventId. Bases come from GET v2 events.")
data class CreateEventBatchRequest(
    // Nullable wrappers distinguish an omitted/null base from a genuine zero with Jackson Kotlin.
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    val expectedLastSequenceNumber: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    val expectedLogRevision: Long?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1–100 events in canonical append order. Every item requires a unique eventId; null items are invalid.")
    val events: List<CreateEventRequest?>,

)
