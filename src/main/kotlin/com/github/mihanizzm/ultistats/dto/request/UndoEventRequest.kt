package com.github.mihanizzm.ultistats.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Nullable wrappers ensure absent/null values cannot silently become a valid zero base. */
data class UndoEventRequest(
    @field:Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val commandId: UUID?,
    @field:Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedLastEventId: UUID?,
    @field:Schema(type = "integer", format = "int64", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedLogRevision: Long?,
)
