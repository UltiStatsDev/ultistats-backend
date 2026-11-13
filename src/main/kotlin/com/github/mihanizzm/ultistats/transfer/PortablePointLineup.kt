package com.github.mihanizzm.ultistats.transfer

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.mihanizzm.ultistats.model.PointSubstitutionSlot
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PortablePointLineup(
    @param:JsonProperty(required = true)
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(
        description = "Null for the first point. Otherwise the preceding active GOAL or CALLAHAN event ID.",
        types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val openingEventId: UUID?,
    @field:Schema(description = "Original confirmed teams in lineup order.")
    val teams: List<PortablePointLineupTeam>,
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    @field:Schema(
        description = "Active substitution-introduced temporary slots for this point. Requires format version 6 and is omitted when empty.",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
    )
    @param:JsonSetter(nulls = Nulls.FAIL)
    val substitutionSlots: List<PortableSubstitutionSlot>? = null,
)

data class PortableSubstitutionSlot(
    val teamId: UUID,
    val slotId: UUID,
    @param:JsonProperty(required = true)
    @field:Schema(minimum = "1")
    val number: Int,
    @param:JsonProperty(required = true)
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val resolvedParticipantId: UUID?,
    @field:Schema(description = "ID of the active PLAYER_SUBSTITUTION in this point whose toParticipantId is slotId.")
    val introducedByEventId: UUID,
) {
    internal fun toDomain() = PointSubstitutionSlot(teamId, slotId, number, resolvedParticipantId, introducedByEventId)
}

data class PortablePointLineupTeam(val teamId: UUID, val entries: List<PortablePointLineupEntry>)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(PortablePointLineupPlayer::class, name = "PLAYER"),
    JsonSubTypes.Type(PortablePointLineupTemporary::class, name = "TEMPORARY"),
)
@Schema(
    oneOf = [PortablePointLineupPlayer::class, PortablePointLineupTemporary::class],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = "PLAYER", schema = PortablePointLineupPlayer::class),
        DiscriminatorMapping(value = "TEMPORARY", schema = PortablePointLineupTemporary::class),
    ],
)
sealed interface PortablePointLineupEntry

data class PortablePointLineupPlayer(val participantId: UUID) : PortablePointLineupEntry {
    @get:Schema(allowableValues = ["PLAYER"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "PLAYER"
}

data class PortablePointLineupTemporary(
    val slotId: UUID,
    @param:JsonProperty(required = true)
    @field:Schema(minimum = "1")
    val number: Int,
    @param:JsonProperty(required = true)
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val resolvedParticipantId: UUID?,
) : PortablePointLineupEntry {
    @get:Schema(allowableValues = ["TEMPORARY"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "TEMPORARY"
}
