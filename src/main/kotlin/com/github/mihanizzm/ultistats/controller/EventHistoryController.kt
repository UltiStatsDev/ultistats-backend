package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.response.PointCompletionHistoryResponse
import com.github.mihanizzm.ultistats.service.PointCompletionHistoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v3/matches/{matchId}/events", produces = [MediaType.APPLICATION_JSON_VALUE])
class EventHistoryController(private val history: PointCompletionHistoryService) {
    @GetMapping
    @Operation(summary = "Read the full ordered journal including approved point completion",
        description = "Each original event retains its time and append sequence. Restored actions have a separate position and nullable elapsed time. " +
            "Use the same point lineup capability as for v2. For append synchronization use the v2 append view.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Ordered history and append position", useReturnTypeSchema = true),
        ApiResponse(responseCode = "404", description = "Match not found", content = [Content()]),
        ApiResponse(responseCode = "409", description = "POINT_COMPLETION_HISTORY_CONFLICT if a stored approval is incompatible with the active point; " +
            "POINT_RESULT_CLIENT_REQUIRED or POINT_LINEUP_CLIENT_REQUIRED if the client lacks support.",
            content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    )
    fun read(@PathVariable matchId: UUID, request: HttpServletRequest): ResponseEntity<PointCompletionHistoryResponse> =
        history.read(matchId, request.pointLineupClient())?.let {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy(POINT_LINEUPS_HEADER).body(it)
        } ?: ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
}
