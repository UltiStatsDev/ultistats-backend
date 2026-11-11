package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.ConfirmPointLineupRequest
import com.github.mihanizzm.ultistats.dto.request.IdentifyPointLineupSlotRequest
import com.github.mihanizzm.ultistats.dto.response.PointLineupResponse
import com.github.mihanizzm.ultistats.dto.response.PointLineupsResponse
import com.github.mihanizzm.ultistats.service.PointLineupService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches/{matchId}/point-lineups")
class PointLineupController(private val lineups: PointLineupService) {
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Read canonical points and their confirmed lineups",
        description = "Returns the current journal revision and whether new confirmations are enabled. Point IDs survive PULL and change when an opening scoring boundary is replaced.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Canonical point lineup snapshot", content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = PointLineupsResponse::class),
        )]),
        ApiResponse(responseCode = "404", description = "Match not found", content = [Content()]),
    )
    fun read(@PathVariable matchId: UUID): ResponseEntity<PointLineupsResponse> =
        lineups.read(matchId)?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{pointId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Confirm both sides of the next point",
        description = "Requires SPORTS_WRITE and CSRF. Each side may be empty or contain up to playersPerTeam entries. " +
            "Use the current logRevision. The same selection for a still-canonical point returns its saved lineup even after PULL or with writes disabled.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Confirmed point", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = PointLineupResponse::class))]),
        ApiResponse(responseCode = "400", description = "Invalid lineup", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "401", description = "Session required", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
        ApiResponse(responseCode = "403", description = "Session permission, identity, transport or CSRF rejected", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
        ApiResponse(responseCode = "404", description = "Match not found", content = [Content()]),
        ApiResponse(responseCode = "409", description = "EVENT_LOG_STALE, POINT_LINEUP_CONFLICT or POINT_LINEUP_WRITE_DISABLED",
            content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    )
    fun confirm(
        @PathVariable matchId: UUID,
        @PathVariable pointId: UUID,
        @RequestBody body: ConfirmPointLineupRequest,
    ): ResponseEntity<PointLineupResponse> =
        lineups.confirm(matchId, pointId, body)?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
            ?: ResponseEntity.notFound().build()

    @PatchMapping("/{pointId}/slots/{slotId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Identify a temporary slot on a completed point or undo its identification",
        description = "Requires SPORTS_WRITE, CSRF and enabled point lineup writes. Supply the current lineup revision " +
            "and closing event ID. Explicit null clears the binding. A same-value request at the current revision is a no-op.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Canonical completed point", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = PointLineupResponse::class))]),
        ApiResponse(responseCode = "400", description = "Invalid identification request", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
        ApiResponse(responseCode = "401", description = "Session required", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
        ApiResponse(responseCode = "403", description = "Session permission, identity, transport or CSRF rejected", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
        ApiResponse(responseCode = "404", description = "Match not found", content = [Content()]),
        ApiResponse(responseCode = "409", description = "POINT_LINEUP_CONFLICT for stale, mismatched or ineligible requests, " +
            "or POINT_LINEUP_WRITE_DISABLED when writes are disabled", content = [Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
        )]),
    )
    fun identify(
        @PathVariable matchId: UUID,
        @PathVariable pointId: UUID,
        @PathVariable slotId: UUID,
        @RequestBody body: IdentifyPointLineupSlotRequest,
    ): ResponseEntity<PointLineupResponse> =
        lineups.identify(matchId, pointId, slotId, body)?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
            ?: ResponseEntity.notFound().build()
}
