package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.ProblemDetail
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/sources")
@SecurityRequirement(name = "obsSourceBearer")
class ObsAutoSourceController(private val service: ObsAutoSourceService) {
    @GetMapping("/scoreboard/auto")
    @Operation(summary = "Read the AUTO scoreboard", description = "HTTPS. AUTO_SCOREBOARD bearer required. No-store. snapshot is null when no started match is eligible.")
    @ApiResponse(
        responseCode = "200", description = "Selected snapshot or explicit null", useReturnTypeSchema = true,
        headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))],
    )
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or revoked bearer",
        headers = [Header(name = "WWW-Authenticate", schema = Schema(type = "string", allowableValues = ["Bearer"]))],
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Wrong source scope or insecure transport",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    fun scoreboard(request: HttpServletRequest) = service.scoreboard(request.getHeaders(HttpHeaders.AUTHORIZATION).toList())

    @GetMapping("/event-log/auto")
    @Operation(summary = "Read the AUTO event log", description = "HTTPS. AUTO_EVENT_LOG bearer required. No-store. snapshot is null when no started match is eligible.")
    @ApiResponse(
        responseCode = "200", description = "Selected snapshot or explicit null", useReturnTypeSchema = true,
        headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))],
    )
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or revoked bearer",
        headers = [Header(name = "WWW-Authenticate", schema = Schema(type = "string", allowableValues = ["Bearer"]))],
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Wrong source scope or insecure transport",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    fun eventLog(request: HttpServletRequest) = service.eventLog(request.getHeaders(HttpHeaders.AUTHORIZATION).toList())
}
