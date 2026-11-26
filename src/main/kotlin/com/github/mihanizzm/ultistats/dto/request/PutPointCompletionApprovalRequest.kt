package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PutPointCompletionApprovalRequest(
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val operationId: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val expectedClosingEventId: UUID,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    val expectedLogRevision: Long,
    @param:JsonProperty(required = true)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(types = ["integer", "null"], format = "int64", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedLineupRevision: Long?,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    val expectedDraftRevision: Long,
)
