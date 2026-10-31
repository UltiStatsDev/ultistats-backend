package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.UndoEventRequest
import com.github.mihanizzm.ultistats.dto.response.UndoCommandOutcome
import com.github.mihanizzm.ultistats.dto.response.UndoCommandResponse
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.result.UndoCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v2/matches/{matchId}/events/undo")
@Tag(name = "Events v2")
class UndoCommandController(private val events: EventService) {
    @PostMapping
    @Operation(summary = "Undo one observed tail with a durable command identity",
        description = "Persist commandId and both preconditions before sending. Identity is (matchId,commandId), with no TTL for the match lifetime. " +
            "Exact replay returns the immutable recorded outcome before checking current state, without writes, revision increments or SSE. " +
            "Valid terminal rejections are also recorded; changed payload is UNDO_COMMAND_CONFLICT. " +
            "resultLogRevision is the committed revision for APPLIED or actually observed revision for REJECTED, never a refreshed canonical position. " +
            "After uncertainty/reload GET the outcome or retry this exact envelope; never replace its identity. Separately GET v2 events before resuming input. " +
            "Invalid bodies and missing matches do not reserve identity. Legacy v1 bodyless undo remains non-idempotent; use this route for replay safety.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Applied or exact successful replay; historical outcome", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = UndoCommandResponse::class))]),
        ApiResponse(responseCode = "400", description = "INVALID_REQUEST: malformed JSON, missing/null UUIDs or missing/null/negative revision; identity not reserved", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: match missing or soft deleted; identity not reserved", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "409", description = "Recorded rejection (EVENT_LOG_STALE, UNDO_LOG_EMPTY, existing lifecycle/sequence code), including exact replay: commandResult present. UNDO_COMMAND_CONFLICT for different payload: commandResult absent; recover original using GET.", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = UndoCommandProblem::class))]),
    )
    fun undo(@PathVariable matchId: UUID, @RequestBody body: UndoEventRequest, request: HttpServletRequest): ResponseEntity<*> =
        when (val result = events.undo(matchId, body)) {
            UndoCommandResult.NotFound -> notFound(request, "Match not found")
            is UndoCommandResult.Invalid -> problem(result.problem, HttpStatus.BAD_REQUEST, request)
            is UndoCommandResult.Conflict -> problem(result.problem, HttpStatus.CONFLICT, request)
            is UndoCommandResult.Recorded -> {
                if (result.response.outcome == UndoCommandOutcome.APPLIED) ResponseEntity.ok(result.response)
                else {
                    val rejection = requireNotNull(result.response.rejection)
                    val detail = MatchProblem(rejection.code, "Undo command rejected", rejection.detail)
                        .toProblemDetail(HttpStatus.CONFLICT, URI.create(request.requestURI))
                    detail.setProperty("commandResult", result.response)
                    ResponseEntity.status(HttpStatus.CONFLICT).body(detail)
                }
            }
        }

    @GetMapping("/{commandId}")
    @Operation(summary = "Recover an immutable undo outcome after response loss or reload",
        description = "Returns a historical APPLIED or REJECTED receipt, not current canonical state. Serialized with commands under the match lock. " +
            "404 means no visible receipt at lookup time, not proof that an in-flight command cannot commit. Keep the original envelope and retry the same ID/payload. " +
            "Receipts have no expiry and are hidden when the match is deleted. GET v2 events separately after a known outcome.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Recorded APPLIED or REJECTED outcome", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = UndoCommandResponse::class))]),
        ApiResponse(responseCode = "400", description = "INVALID_REQUEST: malformed UUID", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: receipt unknown or match missing/deleted; outcome may still be in flight", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    )
    fun outcome(@PathVariable matchId: UUID, @PathVariable commandId: UUID, request: HttpServletRequest): ResponseEntity<*> =
        events.undoOutcome(matchId, commandId)?.let { ResponseEntity.ok(it) }
            ?: notFound(request, "Undo receipt unknown or match unavailable; an in-flight command may still commit")

    private fun notFound(request: HttpServletRequest, detail: String) = problem(
        MatchProblem(MatchProblemCode.RESOURCE_NOT_FOUND, "Resource not found", detail), HttpStatus.NOT_FOUND, request,
    )

    private fun problem(problem: MatchProblem, status: HttpStatus, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(problem.toProblemDetail(status, URI.create(request.requestURI)))
}

@Schema(description = "Undo conflict. commandResult is present only for a durable terminal rejection; absent for changed-payload identity conflict.")
class UndoCommandProblem : ProblemDetail() {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    var code: String? = null
    var commandResult: UndoCommandResponse? = null
}
