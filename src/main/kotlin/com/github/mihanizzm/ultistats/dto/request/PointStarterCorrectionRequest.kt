package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointStarterCorrectionRequest(
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(minimum = "0")
    val expectedLogRevision: Long,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(minimum = "0")
    val expectedLineupRevision: Long,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val expectedClosingEventId: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val fromParticipantId: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val toParticipantId: UUID,
)
