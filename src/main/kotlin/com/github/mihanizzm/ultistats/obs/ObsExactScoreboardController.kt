package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/sources/scoreboard/{matchId}")
@SecurityRequirement(name = "obsSourceBearer")
class ObsExactScoreboardController(private val service: ObsSourceKeyService) {
    @GetMapping
    @Operation(
        summary = "Read an exact scoreboard with a source key",
        description = "HTTPS only. Pass the active EXACT_SCOREBOARD secret in Authorization: Bearer. " +
            "Staff cookies grant no access. No-store. Returns confirmed score, lifecycle and open pauses.",
    )
    @ApiResponse(responseCode = "200", description = "Scoreboard snapshot", useReturnTypeSchema = true)
    @ApiResponse(
        responseCode = "401", description = "OBS_SOURCE_KEY_REQUIRED for a missing, invalid or revoked key",
        headers = [Header(name = "WWW-Authenticate", schema = Schema(type = "string", allowableValues = ["Bearer"]))],
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "OBS_SOURCE_SCOPE_DENIED or PUBLIC_TRANSPORT_READ_ONLY",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404", description = "OBS_SOURCE_MATCH_NOT_FOUND when the bound match has been removed",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    fun read(
        @Parameter(schema = Schema(type = "string", format = "uuid")) @PathVariable matchId: String,
        request: HttpServletRequest,
    ) = service.snapshot(sourceUuid(matchId), request.getHeaders(HttpHeaders.AUTHORIZATION).toList())
}
