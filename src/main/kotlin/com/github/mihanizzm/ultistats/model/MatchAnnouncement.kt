package com.github.mihanizzm.ultistats.model

import java.time.Instant
import java.util.UUID

data class MatchAnnouncement(
    val id: UUID,
    val teamId: UUID?,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val attributionRevision: Long = 0,
    val originalTeamId: UUID? = null,
) {
    // Revision zero uses the effective value. Later revisions may have an originally unknown team.
    val startTeamId: UUID?
        get() = if (attributionRevision == 0L) teamId else originalTeamId
}
