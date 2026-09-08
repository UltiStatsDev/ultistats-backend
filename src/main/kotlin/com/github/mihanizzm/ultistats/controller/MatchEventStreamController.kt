package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.realtime.MatchEventStreamResult
import com.github.mihanizzm.ultistats.realtime.MatchEventStreamService
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches/{matchId}/events/stream")
class MatchEventStreamController(
    private val streamService: MatchEventStreamService,
) {
    @GetMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Подключиться к потоку событий матча")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "SSE-поток событий матча",
                content = [Content(
                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = Schema(type = "string"),
                )],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Матч не найден",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun stream(
        @PathVariable matchId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> = when (val result = streamService.subscribe(matchId)) {
        is MatchEventStreamResult.Opened -> ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .cacheControl(CacheControl.noCache())
            .body(result.emitter)
        MatchEventStreamResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(notFoundProblem(matchId).toProblemDetail(HttpStatus.NOT_FOUND, URI.create(request.requestURI)))
    }

    private fun notFoundProblem(matchId: UUID) = MatchProblem(
        code = MatchProblemCode.RESOURCE_NOT_FOUND,
        title = "Resource not found",
        detail = "Match $matchId not found",
    )
}
