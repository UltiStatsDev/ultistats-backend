package com.github.mihanizzm.ultistats.model

import com.github.mihanizzm.ultistats.model.events.AnnouncementStartEvent
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.model.events.EventType
import com.github.mihanizzm.ultistats.model.events.OnePlayerEvent
import com.github.mihanizzm.ultistats.model.events.SystemEvent
import com.github.mihanizzm.ultistats.model.events.TeamEvent
import com.github.mihanizzm.ultistats.model.events.TwoPlayerEvent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "events")
/**
 * Normalized persistence shape of an event. The public [Event] hierarchy deliberately
 * stays separate so database-only identity, ordering, and soft-deletion fields do not
 * become part of the event API.
 */
data class EventEntity(
    @Id
    val id: UUID,

    @Column(name = "match_id", nullable = false)
    val matchId: UUID,

    @Column(name = "sequence_number", nullable = false)
    val sequenceNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    val eventType: EventType,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "from_participant_id")
    val fromParticipantId: UUID? = null,

    @Column(name = "to_participant_id")
    val toParticipantId: UUID? = null,

    @Column(name = "team_id")
    val teamId: UUID? = null,

    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,

    @Column(name = "creation_fingerprint", length = 64)
    val creationFingerprint: String? = null,

    // Null for pre-V10 rows and inserts made by an older binary. Never backfill lost nanos.
    @Column(name = "original_occurred_at", length = 40)
    val originalOccurredAt: String? = occurredAt.toString(),

    @Column(name = "from_slot_id")
    val fromSlotId: UUID? = null,

    @Column(name = "to_slot_id")
    val toSlotId: UUID? = null,
) {
    /** Trust precision metadata only while it agrees with the physical timestamp.
     * Older binaries keep occurrence time immutable on PATCH. An out-of-band timestamp
     * replacement invalidates the metadata whenever its stored value changes.
     */
    val effectiveOccurredAt: Instant
        get() = requireNotNull(occurredAt.withOriginalPrecision(originalOccurredAt))

    fun toDomain(): Event = when (eventType.category) {
        com.github.mihanizzm.ultistats.model.events.EventCategory.ONE_PLAYER -> {
            OnePlayerEvent(requireNotNull(fromSlotId ?: fromParticipantId), effectiveOccurredAt, eventType)
        }
        com.github.mihanizzm.ultistats.model.events.EventCategory.TWO_PLAYER -> {
            TwoPlayerEvent(
                requireNotNull(fromSlotId ?: fromParticipantId),
                requireNotNull(toSlotId ?: toParticipantId),
                effectiveOccurredAt,
                eventType,
            )
        }
        com.github.mihanizzm.ultistats.model.events.EventCategory.TEAM ->
            TeamEvent(requireNotNull(teamId), effectiveOccurredAt, eventType)
        com.github.mihanizzm.ultistats.model.events.EventCategory.ANNOUNCEMENT ->
            AnnouncementStartEvent(teamId, effectiveOccurredAt)
        com.github.mihanizzm.ultistats.model.events.EventCategory.SYSTEM ->
            SystemEvent(effectiveOccurredAt, eventType)
    }

    companion object {
        fun fromDomain(
            id: UUID,
            matchId: UUID,
            sequenceNumber: Int,
            event: Event,
            creationFingerprint: String? = null,
            slotIds: Set<UUID> = emptySet(),
        ): EventEntity = when (event) {
            is OnePlayerEvent -> EventEntity(
                id, matchId, sequenceNumber, event.type, event.occurredAt,
                fromParticipantId = event.participant.takeUnless(slotIds::contains),
                fromSlotId = event.participant.takeIf(slotIds::contains),
                creationFingerprint = creationFingerprint,
            )
            is TwoPlayerEvent -> EventEntity(
                id, matchId, sequenceNumber, event.type, event.occurredAt,
                fromParticipantId = event.fromParticipant.takeUnless(slotIds::contains),
                toParticipantId = event.toParticipant.takeUnless(slotIds::contains),
                fromSlotId = event.fromParticipant.takeIf(slotIds::contains),
                toSlotId = event.toParticipant.takeIf(slotIds::contains),
                creationFingerprint = creationFingerprint,
            )
            is TeamEvent -> EventEntity(
                id, matchId, sequenceNumber, event.type, event.occurredAt,
                teamId = event.team,
                creationFingerprint = creationFingerprint,
            )
            is AnnouncementStartEvent -> EventEntity(
                id, matchId, sequenceNumber, event.type, event.occurredAt,
                teamId = event.team,
                creationFingerprint = creationFingerprint,
            )
            is SystemEvent -> EventEntity(
                id, matchId, sequenceNumber, event.type, event.occurredAt,
                creationFingerprint = creationFingerprint,
            )
        }
    }
}
