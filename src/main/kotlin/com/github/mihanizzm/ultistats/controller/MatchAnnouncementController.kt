package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.github.mihanizzm.ultistats.model.MatchAnnouncement
import com.github.mihanizzm.ultistats.service.MatchAnnouncementService
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

const val ANNOUNCEMENT_START_ENABLED_HEADER = "X-UltiStats-Announcement-Start-Enabled"

@RestController
@RequestMapping("/api/v1/matches/{matchId}/announcements", produces = [MediaType.APPLICATION_JSON_VALUE])
class MatchAnnouncementController(private val announcements: MatchAnnouncementService) {
    @GetMapping
    @Operation(summary = "List standalone announcements in start time and ID order")
    @ApiResponse(
        responseCode = "200",
        description = "Announcement intervals",
        headers = [Header(
            name = ANNOUNCEMENT_START_ENABLED_HEADER,
            description = "Whether this server permits new announcement starts",
            schema = Schema(type = "boolean"),
        )],
        content = [Content(array = ArraySchema(schema = Schema(implementation = AnnouncementResponse::class)))],
    )
    @ApiResponse(responseCode = "404", description = "Match not found", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    fun listAnnouncements(@PathVariable matchId: UUID, servletRequest: HttpServletRequest): ResponseEntity<*> {
        val intervals = announcements.list(matchId) ?: return rejection(MatchCommandResult.NotFound, servletRequest)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(ANNOUNCEMENT_START_ENABLED_HEADER, announcements.startEnabled.toString())
            .body(intervals.map(MatchAnnouncement::toResponse))
    }

    @PostMapping
    @Operation(summary = "Start a standalone announcement with a stable ID")
    @ApiResponse(responseCode = "201", description = "Announcement started", content = [Content(
        schema = Schema(implementation = AnnouncementResponse::class),
    )])
    @ApiResponse(responseCode = "200", description = "Exact start replay", content = [Content(
        schema = Schema(implementation = AnnouncementResponse::class),
    )])
    @ApiResponse(responseCode = "400", description = "Invalid announcement", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(responseCode = "404", description = "Match not found", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(responseCode = "409", description = "Start conflict or starting disabled", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    fun startAnnouncement(
        @PathVariable matchId: UUID,
        @RequestBody request: StartAnnouncementRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = when (val result = announcements.start(matchId, request.id, request.teamId, request.startedAt)) {
        is MatchCommandResult.Success -> ResponseEntity.status(
            if (result.value.created) HttpStatus.CREATED else HttpStatus.OK,
        ).body(result.value.interval.toResponse())
        else -> rejection(result, servletRequest)
    }

    @PostMapping("/{id}/end")
    @Operation(summary = "End a standalone announcement")
    @ApiResponse(responseCode = "200", description = "Announcement ended or exact end replay", content = [Content(
        schema = Schema(implementation = AnnouncementResponse::class),
    )])
    @ApiResponse(responseCode = "400", description = "Invalid end time", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(responseCode = "404", description = "Match or announcement not found", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(responseCode = "409", description = "End conflict", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    fun endAnnouncement(
        @PathVariable matchId: UUID,
        @PathVariable id: UUID,
        @RequestBody request: EndAnnouncementRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = when (val result = announcements.end(matchId, id, request.endedAt)) {
        is MatchCommandResult.Success -> ResponseEntity.ok(result.value.toResponse())
        else -> rejection(result, servletRequest)
    }

    @PatchMapping("/{id}/team", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Correct the team of a closed standalone announcement after the match finishes",
        description = "Send the current attributionRevision. A current no-op preserves the revision. " +
            "For ANNOUNCEMENT_ATTRIBUTION_STALE, read the current announcement before making another correction.",
    )
    @ApiResponse(responseCode = "200", description = "Current announcement", content = [Content(
        schema = Schema(implementation = AnnouncementResponse::class),
    )])
    @ApiResponse(responseCode = "400", description = "Invalid payload or team outside the match", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(responseCode = "401", description = "Staff session required")
    @ApiResponse(responseCode = "403", description = "Permission, origin, session context or CSRF check failed")
    @ApiResponse(responseCode = "404", description = "Match or announcement not found", content = [Content(
        mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class),
    )])
    @ApiResponse(
        responseCode = "409",
        description = "ANNOUNCEMENT_ATTRIBUTION_STALE, ANNOUNCEMENT_WRITE_DISABLED, ANNOUNCEMENT_NOT_CLOSED or MATCH_NOT_FINISHED",
        content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))],
    )
    fun correctAnnouncementTeam(
        @PathVariable matchId: UUID,
        @PathVariable id: UUID,
        @RequestBody request: CorrectAnnouncementTeamRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = when (
        val result = announcements.correctTeam(matchId, id, request.teamId, request.expectedAttributionRevision)
    ) {
        is MatchCommandResult.Success -> ResponseEntity.ok(result.value.toResponse())
        else -> rejection(result, servletRequest)
    }

    private fun rejection(result: MatchCommandResult<*>, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val (status, problem) = when (result) {
            MatchCommandResult.NotFound -> HttpStatus.NOT_FOUND to MatchProblem(
                MatchProblemCode.RESOURCE_NOT_FOUND, "Resource not found", "Match or announcement not found",
            )
            is MatchCommandResult.InvalidRequest -> HttpStatus.BAD_REQUEST to result.problem
            is MatchCommandResult.InvalidState -> HttpStatus.CONFLICT to result.problem
            is MatchCommandResult.Conflict -> HttpStatus.CONFLICT to result.problem
            is MatchCommandResult.Success -> error("Expected a rejected announcement command")
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problem.toProblemDetail(status, URI.create(request.requestURI)))
    }
}

data class StartAnnouncementRequest(
    val id: UUID,
    @field:Schema(types = ["string", "null"], format = "uuid")
    val teamId: UUID? = null,
    @field:JsonDeserialize(using = AnnouncementInstantDeserializer::class)
    val startedAt: Instant,
)

data class EndAnnouncementRequest(
    @field:JsonDeserialize(using = AnnouncementInstantDeserializer::class)
    val endedAt: Instant,
)

@JsonDeserialize(using = CorrectAnnouncementTeamDeserializer::class)
data class CorrectAnnouncementTeamRequest(
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val teamId: UUID?,
    @field:Schema(type = "integer", format = "int64", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val expectedAttributionRevision: Long,
)

class CorrectAnnouncementTeamDeserializer : JsonDeserializer<CorrectAnnouncementTeamRequest>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): CorrectAnnouncementTeamRequest {
        val body = parser.readValueAsTree<JsonNode>()
        val team = body["teamId"]
        val revision = body["expectedAttributionRevision"]
        if (team == null || revision == null || !revision.isIntegralNumber ||
            !revision.canConvertToLong() || revision.asLong() < 0
        ) {
            return context.reportInputMismatch(
                CorrectAnnouncementTeamRequest::class.java, "teamId and a nonnegative integer expectedAttributionRevision are required",
            )
        }
        val teamId = if (team.isNull) null else {
            val text = team.textValue()
            val uuid = text?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            uuid?.takeIf { it.toString().equals(text, ignoreCase = true) } ?: return context.reportInputMismatch(
                CorrectAnnouncementTeamRequest::class.java, "teamId must be a UUID or null",
            )
        }
        return CorrectAnnouncementTeamRequest(teamId, revision.asLong())
    }
}

class AnnouncementInstantDeserializer : JsonDeserializer<Instant>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Instant {
        if (parser.currentToken == JsonToken.VALUE_STRING) {
            try {
                return Instant.parse(parser.text)
            } catch (_: DateTimeParseException) {
                // Use the same API error for invalid text and non-string tokens.
            }
        }
        return context.reportInputMismatch(Instant::class.java, "Announcement timestamp must be an ISO-8601 instant")
    }
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class AnnouncementResponse(
    val id: UUID,
    @field:Schema(types = ["string", "null"], format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
    val teamId: UUID?,
    val startedAt: Instant,
    @field:Schema(types = ["string", "null"], format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
    val endedAt: Instant?,
    @field:Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
    val attributionRevision: Long,
)

private fun MatchAnnouncement.toResponse() = AnnouncementResponse(id, teamId, startedAt, endedAt, attributionRevision)
