package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PutPointResultRequest(
    @field:Schema(description = "Client-generated operation UUID and canonical marker ID.")
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val operationId: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val scoringTeamId: UUID,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(minimum = "0")
    val expectedLogRevision: Long,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(type = "integer", format = "int32", minimum = "0", maximum = "2147483647")
    val expectedLastSequenceNumber: Long,
)
