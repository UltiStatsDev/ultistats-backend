package com.github.mihanizzm.ultistats.service

enum class PointLineupClient {
    LEGACY,
    LINEUPS,
    SUBSTITUTIONS,
    INCOMING_RESERVES;

    val supportsSubstitutions: Boolean get() = this == SUBSTITUTIONS || this == INCOMING_RESERVES
}
