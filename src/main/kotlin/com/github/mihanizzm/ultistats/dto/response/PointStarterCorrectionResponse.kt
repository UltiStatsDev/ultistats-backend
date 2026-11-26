package com.github.mihanizzm.ultistats.dto.response

import com.github.mihanizzm.ultistats.model.events.EventType
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointStarterCorrectionPreview(
    val pointId: UUID,
    val teamId: UUID,
    @field:Schema(minimum = "0", description = "Zero-based position in the initial team entries.")
    val entryIndex: Int,
    val fromParticipantId: UUID,
    val toParticipantId: UUID,
    val boundary: PointStarterCorrectionBoundary,
    val changedEvents: List<PointStarterCorrectionEvent>,
)

data class PointStarterCorrectionBoundary(val kind: PointStarterCorrectionBoundaryKind, val eventId: UUID)
enum class PointStarterCorrectionBoundaryKind { OUTGOING_SUBSTITUTION, POINT_CLOSE }

data class PointStarterCorrectionEvent(
    val eventId: UUID,
    val type: EventType,
    @field:Schema(description = "FROM denotes participantId for one-player events.")
    val sides: List<PointStarterCorrectionSide>,
)
enum class PointStarterCorrectionSide { FROM, TO }

data class PointStarterCorrectionResponse(
    val logRevision: Long,
    val point: PointLineupResponse,
    val correction: PointStarterCorrectionPreview,
)
