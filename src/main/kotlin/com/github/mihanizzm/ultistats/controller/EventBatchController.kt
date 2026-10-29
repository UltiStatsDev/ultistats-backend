package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.CreateEventBatchRequest
import com.github.mihanizzm.ultistats.dto.response.EventLogResponse
import com.github.mihanizzm.ultistats.service.EventIdempotencyConflictException
import com.github.mihanizzm.ultistats.service.EventService
import com.github.mihanizzm.ultistats.service.result.EventBatchResult
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
@RequestMapping("/api/v2/matches/{matchId}/events")
@Tag(name = "Events v2", description = "Offline-first event synchronization. v1 remains supported and is not deprecated pending frontend migration and field acceptance.")
class EventBatchController(private val events: EventService) {
    @GetMapping
    @Operation(summary = "Read a consistent canonical event log and synchronization position",
        description = "Returns active events in sequenceNumber order, the highest ever allocated number (including deleted events), and logRevision under the match lock. Use both position fields for the next batch. No events are reordered by occurredAt.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Consistent snapshot", content = [Content(schema = Schema(implementation = EventLogResponse::class))]),
        ApiResponse(responseCode = "404", description = "Match not found"),
    )
    fun snapshot(@PathVariable matchId: UUID): ResponseEntity<EventLogResponse> =
        events.snapshot(matchId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @PostMapping("/batch")
    @Operation(summary = "Atomically append 1–100 ordered events with stable client UUIDs",
        description = "Server assigns consecutive sequence numbers in request order. Both expected bases are required. " +
            "The entire batch commits or nothing commits; lifecycle, participants, timestamps and EventSequencePolicy match v1. " +
            "Equal timestamps are allowed; backwards timestamps are rejected. Score is recalculated once. " +
            "201 confirms a new batch; 200 replays the exact original batch (same bases, UUIDs, payloads and order), even after later appends. " +
            "PATCH/DELETE/undo through either API invalidate earlier batch retries. Partial, reordered and subset retries are 409. " +
            "On 409 stop sending the dependent queue, GET this v2 log and reconcile with the operator; the server never merges or repairs events. " +
            "v1 remains supported without deprecation until frontend migration and real field acceptance are completed separately.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "New batch committed", content = [Content(schema = Schema(implementation = EventLogResponse::class))]),
        ApiResponse(responseCode = "200", description = "Exact retry, no writes or repeated notifications", content = [Content(schema = Schema(implementation = EventLogResponse::class))]),
        ApiResponse(responseCode = "400", description = "Invalid body, bases, batch size, missing/repeated UUID or invalid event participants", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = EventBatchProblem::class))]),
        ApiResponse(responseCode = "404", description = "Match not found"),
        ApiResponse(responseCode = "409", description = "EVENT_LOG_STALE, EVENT_BATCH_CONFLICT, EVENT_IDEMPOTENCY_CONFLICT, EVENT_SEQUENCE_VIOLATION or existing lifecycle/timestamp code. No writes. Includes current position and offending eventId/eventIndex when applicable (zero-based index).", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = EventBatchProblem::class))]),
    )
    fun append(
        @PathVariable matchId: UUID,
        @RequestBody body: CreateEventBatchRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        // Catch only outside the transactional proxy: a global UUID race has rolled back by this point.
        val result = try {
            events.appendBatch(matchId, body)
        } catch (exception: EventIdempotencyConflictException) {
            val snapshot = events.snapshot(matchId) ?: return ResponseEntity.notFound().build<Void>()
            EventBatchResult.Rejected(
                MatchProblem(MatchProblemCode.EVENT_IDEMPOTENCY_CONFLICT, "Event idempotency conflict", "Event ${exception.eventId} already exists with a different payload"),
                snapshot.lastSequenceNumber, snapshot.logRevision, exception.eventId,
                body.events.indexOfFirst { it?.eventId == exception.eventId }.takeIf { it >= 0 },
            )
        }
        return when (result) {
            EventBatchResult.NotFound -> ResponseEntity.notFound().build<Void>()
            is EventBatchResult.Success -> ResponseEntity.status(if (result.replayed) HttpStatus.OK else HttpStatus.CREATED).body(result.response)
            is EventBatchResult.Rejected -> {
                val status = if (result.badRequest) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT
                val problem = result.problem.toProblemDetail(status, URI.create(request.requestURI)).apply {
                    setProperty("lastSequenceNumber", result.lastSequenceNumber)
                    setProperty("logRevision", result.logRevision)
                    result.eventId?.let { setProperty("eventId", it) }
                    result.eventIndex?.let { setProperty("eventIndex", it) }
                }
                ResponseEntity.status(status).body(problem)
            }
        }
    }
}

@Schema(description = "ProblemDetail extensions for reconciliation. Malformed JSON may return the common INVALID_REQUEST ProblemDetail without position fields.")
class EventBatchProblem : ProblemDetail() {
    @Schema(description = "Stable machine-readable conflict or validation code")
    var code: String? = null
    var lastSequenceNumber: Int? = null
    var logRevision: Long? = null
    var eventId: UUID? = null
    @Schema(description = "Zero-based offending event index")
    var eventIndex: Int? = null
}
