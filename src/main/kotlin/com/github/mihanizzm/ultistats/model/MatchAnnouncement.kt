package com.github.mihanizzm.ultistats.model

import java.time.Instant
import java.util.UUID

data class MatchAnnouncement(
    val id: UUID,
    val teamId: UUID?,
    val startedAt: Instant,
    val endedAt: Instant? = null,
)
