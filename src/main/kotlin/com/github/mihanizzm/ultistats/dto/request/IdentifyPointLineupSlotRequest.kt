package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class IdentifyPointLineupSlotRequest(
    @param:JsonProperty(required = true)
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val participantId: UUID?,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    @field:Schema(type = "integer", format = "int64", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedLineupRevision: Long,
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:JsonDeserialize(using = PointLineupUuidDeserializer::class)
    @field:Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedClosingEventId: UUID,
)

class PointLineupUuidDeserializer : JsonDeserializer<UUID>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): UUID {
        if (parser.currentToken == JsonToken.VALUE_STRING) {
            val value = parser.text
            val uuid = runCatching { UUID.fromString(value) }.getOrNull()
            if (uuid != null && uuid.toString().equals(value, ignoreCase = true)) return uuid
        }
        return context.reportInputMismatch(UUID::class.java, "Expected a UUID string")
    }
}
