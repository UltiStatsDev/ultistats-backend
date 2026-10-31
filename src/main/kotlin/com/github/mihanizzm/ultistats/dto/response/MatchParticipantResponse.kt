package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonInclude
import com.github.mihanizzm.ultistats.model.MatchParticipant
import com.github.mihanizzm.ultistats.model.MatchParticipantKind
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MatchParticipantResponse(
    val participantId: UUID,
    val kind: MatchParticipantKind,
    @field:Schema(types = ["integer", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val unknownSlot: Int?,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val firstName: String?,
    @field:Schema(types = ["string", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val lastName: String?,
    val displayName: String,
    @field:Schema(types = ["integer", "null"], requiredMode = Schema.RequiredMode.REQUIRED)
    val number: Int?,
    @field:Schema(
        description = "URL фотографии игрока; null для неизвестного участника или игрока без фотографии",
        types = ["string", "null"],
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val photoUrl: String?,
) {
    companion object {
        fun from(participant: MatchParticipant, photoUrl: String?) = MatchParticipantResponse(
            participantId = participant.participantId,
            kind = participant.kind,
            unknownSlot = participant.unknownSlot,
            firstName = participant.firstName,
            lastName = participant.lastName,
            displayName = participant.displayName,
            number = participant.number,
            photoUrl = photoUrl.takeIf { participant.kind == MatchParticipantKind.PLAYER },
        )
    }
}
