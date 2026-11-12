package com.github.mihanizzm.ultistats.model.events

import com.github.mihanizzm.ultistats.model.PointLineupEntry
import com.github.mihanizzm.ultistats.model.PointLineupPlayer
import com.github.mihanizzm.ultistats.model.PointLineupTeam
import com.github.mihanizzm.ultistats.model.PointLineupTemporary
import java.util.UUID

internal val PointLineupEntry.actorId: UUID
    get() = when (this) {
        is PointLineupPlayer -> participantId
        is PointLineupTemporary -> slotId
    }

internal fun List<StoredEvent>.substitutions(): List<TwoPlayerEvent> =
    mapNotNull { if (it.event.type == EventType.PLAYER_SUBSTITUTION) it.event as TwoPlayerEvent else null }

internal fun List<PointLineupTeam>.afterSubstitution(event: TwoPlayerEvent): List<PointLineupTeam> =
    map { team -> team.copy(entries = team.entries.map {
        if (it.actorId == event.fromParticipant) PointLineupPlayer(event.toParticipant) else it
    }) }
