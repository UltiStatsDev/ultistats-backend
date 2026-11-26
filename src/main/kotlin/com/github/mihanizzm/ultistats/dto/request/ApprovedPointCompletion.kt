package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.ArraySchema
import java.util.UUID

data class ApprovedPointCompletion(
    val pointId: UUID,
    val closingEventId: UUID,
    @param:JsonSetter(contentNulls = Nulls.FAIL)
    @field:ArraySchema(minItems = 1, maxItems = 500)
    val insertions: List<PointCompletionInsertion>,
)
