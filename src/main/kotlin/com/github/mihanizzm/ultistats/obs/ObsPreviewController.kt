package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/previews")
@SecurityRequirement(name = "staffSession")
@ApiResponse(responseCode = "200", description = "Existing confirmed snapshot; AUTO wraps a nullable snapshot", useReturnTypeSchema = true,
    headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))])
@ApiResponse(responseCode = "401", description = "Missing, expired or revoked staff session")
@ApiResponse(responseCode = "403", description = "OBS_ADMIN required; HTTPS only. Viewer bearer grants no access")
@ApiResponse(responseCode = "404", description = "Exact match removed or not found")
class ObsPreviewController(private val previews: ObsPreviewService) {
    @GetMapping("/scoreboard/{matchId}")
    @Operation(summary = "Preview an exact scoreboard in the OBS_ADMIN HTTPS session")
    fun scoreboard(@PathVariable matchId: String) = previews.scoreboard(sourceUuid(matchId))

    @GetMapping("/event-log/{matchId}")
    @Operation(summary = "Preview an exact Event Log in the OBS_ADMIN HTTPS session")
    fun eventLog(@PathVariable matchId: String) = previews.eventLog(sourceUuid(matchId))

    @GetMapping("/scoreboard/auto")
    @Operation(summary = "Preview the existing AUTO scoreboard selection in the OBS_ADMIN HTTPS session")
    fun autoScoreboard() = previews.autoScoreboard()

    @GetMapping("/event-log/auto")
    @Operation(summary = "Preview the existing AUTO Event Log selection in the OBS_ADMIN HTTPS session")
    fun autoEventLog() = previews.autoEventLog()
}
