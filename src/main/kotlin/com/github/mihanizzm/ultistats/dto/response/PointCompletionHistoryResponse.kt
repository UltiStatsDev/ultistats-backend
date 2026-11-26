package com.github.mihanizzm.ultistats.dto.response

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.github.mihanizzm.ultistats.dto.request.PointCompletionInsertion
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PointCompletionHistoryResponse(
    val entries: List<ProjectedEvent>,
    @field:Schema(description = "Highest allocated append sequence, including retired rows. Restored actions do not advance it.")
    val lastSequenceNumber: Int,
    val logRevision: Long,
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ProjectedEvent.Original::class, name = "ORIGINAL"),
    JsonSubTypes.Type(value = ProjectedEvent.Restored::class, name = "RESTORED"),
)
@Schema(discriminatorProperty = "kind", oneOf = [ProjectedEvent.Original::class, ProjectedEvent.Restored::class],
    discriminatorMapping = [
        DiscriminatorMapping(value = "ORIGINAL", schema = ProjectedEvent.Original::class),
        DiscriminatorMapping(value = "RESTORED", schema = ProjectedEvent.Restored::class),
    ])
sealed interface ProjectedEvent {
    @get:Schema(description = "One-based display position in this response. It is independent of the append cursor.")
    val position: Int

    @Schema(name = "Original")
    data class Original(override val position: Int, val event: EventResponse) : ProjectedEvent

    @Schema(name = "Restored")
    data class Restored(
        override val position: Int,
        val pointId: UUID,
        val closingEventId: UUID,
        val insertion: PointCompletionInsertion,
    ) : ProjectedEvent
}
