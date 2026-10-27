package com.github.mihanizzm.ultistats.dto.request

import org.openapitools.jackson.nullable.JsonNullable
import java.time.Instant
import java.util.UUID

data class PatchPlayerRequest(
    val firstName: JsonNullable<String> = JsonNullable.undefined(),
    val lastName: JsonNullable<String> = JsonNullable.undefined(),
)

data class PatchTeamRequest(
    val name: JsonNullable<String> = JsonNullable.undefined(),
    val city: JsonNullable<String> = JsonNullable.undefined(),
)

data class PatchMatchRequest(
    val teamIds: JsonNullable<List<UUID>> = JsonNullable.undefined(),
    val playersPerTeam: JsonNullable<Int> = JsonNullable.undefined(),
    val plannedStartTimestamp: JsonNullable<Instant> = JsonNullable.undefined(),
)
