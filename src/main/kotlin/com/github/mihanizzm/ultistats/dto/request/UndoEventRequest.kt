package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** Nullable wrappers ensure absent/null values cannot silently become a valid zero base. */
data class UndoEventRequest(
    @field:Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val commandId: UUID?,
    @field:Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedLastEventId: UUID?,
    @field:Schema(type = "integer", format = "int64", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    val expectedLogRevision: Long?,
)

/** Only this additive command boundary is strict; frozen append deserialization is unchanged. */
class UndoLogRevisionDeserializer : JsonDeserializer<Long>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Long {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long::class.java, "expectedLogRevision must be a JSON integer")
        }
        return parser.longValue
    }
}
