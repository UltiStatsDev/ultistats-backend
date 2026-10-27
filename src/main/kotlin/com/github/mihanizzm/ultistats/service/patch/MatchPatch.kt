package com.github.mihanizzm.ultistats.service.patch

import java.time.Instant
import java.util.UUID

data class MatchPatch(
    val teamIds: FieldPatch<List<UUID>> = FieldPatch.Omitted,
    val playersPerTeam: FieldPatch<Int> = FieldPatch.Omitted,
    val plannedStartTimestamp: FieldPatch<Instant> = FieldPatch.Omitted,
) {
    fun isEmpty(): Boolean =
        teamIds == FieldPatch.Omitted &&
            playersPerTeam == FieldPatch.Omitted &&
            plannedStartTimestamp == FieldPatch.Omitted
}
