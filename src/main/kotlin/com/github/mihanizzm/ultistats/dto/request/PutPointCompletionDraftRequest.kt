package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PutPointCompletionDraftRequest(
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
    @param:JsonSetter(contentNulls = Nulls.FAIL)
    @field:ArraySchema(maxItems = 500)
    val insertions: List<PointCompletionInsertion>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PointCompletionInsertion(
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val id: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    @field:Schema(types = ["string", "null"], format = "uuid", description = "Original event ID, or null to insert at the start.")
    val afterEventId: UUID?,
    val type: PointCompletionActionType,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    val fromActorId: UUID,
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    @field:Schema(types = ["string", "null"], format = "uuid")
    val toActorId: UUID?,
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(types = ["integer", "null"], format = "int64", description = "Elapsed milliseconds from match start. Null means unknown.")
    val elapsedOffsetMs: Long?,
)

/** Only gameplay actions with player attribution. The canonical POINT_RESULT cannot be inserted. */
enum class PointCompletionActionType {
    PULL, BRICK, PICKUP, PASS, INCOMPLETE_PASS, GOAL, BLOCK, BLOCK_MARKER, BLOCK_FIELD,
    INTERCEPTION, CALLAHAN, PLAYER_SUBSTITUTION, POSSESSION_SET;

    fun eventType(): EventType = EventType.valueOf(name)

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String): PointCompletionActionType = valueOf(value)
    }
}
