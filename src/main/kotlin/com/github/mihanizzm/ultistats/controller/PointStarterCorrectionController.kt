package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.PointStarterCorrectionRequest
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionPreview
import com.github.mihanizzm.ultistats.dto.response.PointStarterCorrectionResponse
import com.github.mihanizzm.ultistats.service.PointLineupService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(
    "/api/v1/matches/{matchId}/point-lineups/{pointId}/starter-correction",
    consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE],
)
@ApiResponses(
    ApiResponse(responseCode = "400", description = "Invalid correction request", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )]),
    ApiResponse(responseCode = "401", description = "Session required", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )]),
    ApiResponse(responseCode = "403", description = "Session permission, identity, transport or CSRF rejected", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )]),
    ApiResponse(responseCode = "404", description = "Match not found", content = [Content()]),
    ApiResponse(responseCode = "409",
        description = "EVENT_LOG_STALE, POINT_LINEUP_CONFLICT, POINT_LINEUP_WRITE_DISABLED or POINT_LINEUP_CLIENT_REQUIRED",
        content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
)
class PointStarterCorrectionController(private val lineups: PointLineupService) {
    @PostMapping("/preview")
    @ApiResponse(responseCode = "200", description = "Proposed correction", useReturnTypeSchema = true)
    @Operation(summary = "Preview a known starter correction on a completed point",
        description = "Requires SPORTS_WRITE, CSRF and enabled point lineup writes. Returns the exact changes for the supplied revisions.",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER,
            schema = Schema(allowableValues = ["1", "2", "3"]))])
    fun preview(
        @PathVariable matchId: UUID,
        @PathVariable pointId: UUID,
        @RequestBody body: PointStarterCorrectionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<PointStarterCorrectionPreview> =
        lineups.previewStarterCorrection(matchId, pointId, body, request.pointLineupClient())
            ?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping
    @ApiResponse(responseCode = "200", description = "Applied correction and current point", useReturnTypeSchema = true)
    @Operation(summary = "Correct a known starter on a completed point",
        description = "Requires SPORTS_WRITE, CSRF and enabled point lineup writes. Revalidates and applies the proposal in one transaction.",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER,
            schema = Schema(allowableValues = ["1", "2", "3"]))])
    fun save(
        @PathVariable matchId: UUID,
        @PathVariable pointId: UUID,
        @RequestBody body: PointStarterCorrectionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<PointStarterCorrectionResponse> =
        lineups.correctStarter(matchId, pointId, body, request.pointLineupClient())
            ?.let { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
            ?: ResponseEntity.notFound().build()
}
