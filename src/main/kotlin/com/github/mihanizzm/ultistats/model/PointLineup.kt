package com.github.mihanizzm.ultistats.model

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointLineup(val revision: Long, val teams: List<PointLineupTeam>)
data class PointLineupTeam(val teamId: UUID, val entries: List<PointLineupEntry>)

@Schema(
    oneOf = [PointLineupPlayer::class, PointLineupTemporary::class],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = "PLAYER", schema = PointLineupPlayer::class),
        DiscriminatorMapping(value = "TEMPORARY", schema = PointLineupTemporary::class),
    ],
)
sealed interface PointLineupEntry

data class PointLineupPlayer(val participantId: UUID) : PointLineupEntry {
    @get:Schema(allowableValues = ["PLAYER"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "PLAYER"
}

data class PointLineupTemporary(
    val slotId: UUID,
    val number: Int,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val resolvedParticipantId: UUID? = null,
) : PointLineupEntry {
    @get:Schema(allowableValues = ["TEMPORARY"], requiredMode = Schema.RequiredMode.REQUIRED)
    val kind: String get() = "TEMPORARY"
}
