package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.PutPointResultRequest
import com.github.mihanizzm.ultistats.dto.response.PointResultResponse
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
class PointResultController(private val events: EventService) {
    @PutMapping("/api/v1/matches/{matchId}/point-lineups/{pointId}/result", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Close the current point with a team result",
        description = "Requires SPORTS_WRITE, CSRF and capability 4. Writes one POINT_RESULT with the client operation ID and " +
            "server registration time. Only fresh commands require the writer flag and matching event log position. " +
            "An exact retry of a still-canonical result returns the same marker and successor with the current journal.",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER, required = true,
            schema = Schema(type = "string", allowableValues = ["4"]))],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Accepted or replayed canonical result", content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = PointResultResponse::class),
        )]),
        ApiResponse(responseCode = "400", description = "INVALID_REQUEST", content = [Content(
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
            description = "POINT_RESULT_CLIENT_REQUIRED, POINT_RESULT_WRITE_DISABLED, POINT_RESULT_CONFLICT, EVENT_LOG_STALE, " +
                "EVENT_IDEMPOTENCY_CONFLICT, MATCH_NOT_IN_PROGRESS, EVENT_SEQUENCE_VIOLATION, EVENT_BEFORE_START or EVENT_OUT_OF_ORDER",
            content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    )
    fun put(
        @PathVariable matchId: UUID,
        @PathVariable pointId: UUID,
        @RequestBody body: PutPointResultRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        fun reject(status: HttpStatus, problem: MatchProblem) =
            ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(problem.toProblemDetail(status, URI.create(request.requestURI)))
        return when (val result = events.putPointResult(matchId, pointId, body, request.pointLineupClient())) {
            is MatchCommandResult.Success ->
                ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy(POINT_LINEUPS_HEADER).body(result.value)
            MatchCommandResult.NotFound -> ResponseEntity.notFound().build<Void>()
            is MatchCommandResult.InvalidRequest -> reject(HttpStatus.BAD_REQUEST, result.problem)
            is MatchCommandResult.InvalidState -> reject(HttpStatus.CONFLICT, result.problem)
            is MatchCommandResult.Conflict -> reject(HttpStatus.CONFLICT, result.problem)
        }
    }
}
