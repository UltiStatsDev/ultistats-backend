package com.github.mihanizzm.ultistats.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class ConfirmPointLineupRequest(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    @field:JsonDeserialize(using = UndoLogRevisionDeserializer::class)
    val expectedLogRevision: Long?,
    @param:JsonSetter(contentNulls = Nulls.FAIL)
    val teams: List<ConfirmPointLineupTeam>,
)

data class ConfirmPointLineupTeam(
    val teamId: UUID,
    @param:JsonSetter(contentNulls = Nulls.FAIL)
    val entries: List<ConfirmPointLineupEntry>,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(ConfirmPointLineupPlayer::class, name = "PLAYER"),
    JsonSubTypes.Type(ConfirmPointLineupTemporary::class, name = "TEMPORARY"),
)
@Schema(
    oneOf = [ConfirmPointLineupPlayer::class, ConfirmPointLineupTemporary::class],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = "PLAYER", schema = ConfirmPointLineupPlayer::class),
        DiscriminatorMapping(value = "TEMPORARY", schema = ConfirmPointLineupTemporary::class),
    ],
)
sealed interface ConfirmPointLineupEntry

data class ConfirmPointLineupPlayer(val participantId: UUID) : ConfirmPointLineupEntry {
    @get:Schema(allowableValues = ["PLAYER"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "PLAYER"
}

data class ConfirmPointLineupTemporary(
    val slotId: UUID,
    @field:Schema(minimum = "1")
    @field:JsonDeserialize(using = PointLineupNumberDeserializer::class)
    @param:JsonProperty(required = true)
    @param:JsonSetter(nulls = Nulls.FAIL)
    val number: Int,
) : ConfirmPointLineupEntry {
    @get:Schema(allowableValues = ["TEMPORARY"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "TEMPORARY"
}

class PointLineupNumberDeserializer : JsonDeserializer<Int>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Int {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Int::class.java, "Temporary slot number must be a JSON integer")
        }
        return parser.intValue
    }
}
