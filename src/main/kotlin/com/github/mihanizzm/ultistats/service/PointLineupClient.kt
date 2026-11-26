package com.github.mihanizzm.ultistats.service

enum class PointLineupClient {
    LEGACY,
    LINEUPS,
    SUBSTITUTIONS,
    INCOMING_RESERVES,
    POINT_RESULTS;

    val supportsSubstitutions: Boolean get() = this >= SUBSTITUTIONS
    val supportsIncomingReserves: Boolean get() = this >= INCOMING_RESERVES
    val supportsPointResults: Boolean get() = this >= POINT_RESULTS
}
