package com.github.mihanizzm.ultistats.controller

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.mihanizzm.ultistats.transfer.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import java.net.URI

@RestController
@RequestMapping("/api/v1/matches")
class MatchTransferController(private val service: MatchTransferService) {
    // Portable files are a strict contract; leave the existing API's mapper untouched.
    private val reader = JsonMapper.builder()
        .addModule(JavaTimeModule())
        .addModule(KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build())
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build()

    @PostMapping("/export", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Export selected matches as portable JSON",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER, description = "Send 4 to accept format 7 with POINT_RESULT.")],
        description = "Uses version 7 when any selected match has an active POINT_RESULT. Otherwise uses version 6 when any selected match has an active substitution that introduces a temporary reserve. Otherwise uses version 5 when any selected match has an active PLAYER_SUBSTITUTION, including in finished matches. Otherwise uses version 4 with canonical confirmed point lineups, version 3 with POSSESSION_SET, version 2 with standalone announcements, or version 1. Preserves selected order, historical snapshots, active events and their timestamps. Unreachable lineups and soft-deleted events are excluded.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Portable package", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferPackage::class), examples = [ExampleObject(name = "portableMatch", value = MatchTransferExamples.PACKAGE), ExampleObject(name = "pointSubstitution", value = MatchTransferExamples.SUBSTITUTION_PACKAGE), ExampleObject(name = "incomingReserve", value = MatchTransferExamples.INCOMING_RESERVE_PACKAGE), ExampleObject(name = "pointResult", value = MatchTransferExamples.POINT_RESULT_PACKAGE)])]),
        ApiResponse(responseCode = "400", description = "Invalid selection", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "404", description = "A selected match does not exist or is deleted", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "409", description = "POINT_RESULT_CLIENT_REQUIRED for a selection with POINT_RESULT without capability 4, or POINT_LINEUP_EXPORT_UNSUPPORTED for a temporary reference without an exportable point", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    ])
    fun export(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchExportRequest::class), examples = [ExampleObject(value = MatchTransferExamples.SELECTION)])])
        @RequestBody body: String,
        request: HttpServletRequest,
    ): ResponseEntity<MatchTransferPackage> {
        val data = service.export(decode(body, MatchExportRequest::class.java).matchIds, request.pointLineupClient())
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ultistats-matches.json\"")
            .apply {
                if (data.formatVersion == POINT_RESULT_TRANSFER_VERSION) {
                    cacheControl(CacheControl.noStore()).varyBy(POINT_LINEUPS_HEADER)
                }
            }
            .body(data)
    }

    @PostMapping("/import", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Import a portable package as independent copies",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER, description = "Send 4 to import format 7 with POINT_RESULT.")],
        description = "The format version must match the contents. Version 7 requires an active POINT_RESULT, capability 4 and enabled point result writes. Version 7 preserves the marker registration timestamp and closes the point using the remapped marker ID. Version 6 requires at least one active introduced reserve. Version 5 carries active substitutions for known roster players. Versions 5 through 7 copy substitutions with remapped actors and their original journal positions and times. Confirmed lineups are copied with fresh point and slot IDs, revision 1 and current identification. Each included feature requires its own write flag for incoming reserves, point substitutions, point lineups, POSSESSION_SET and standalone announcements. The entire package is validated before any writes and committed atomically. Each import creates a new copy with preserved status, timestamps, snapshots and event order and recalculated scores.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Source-to-created identity maps", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferResult::class), examples = [ExampleObject(value = MatchTransferExamples.RESULT)])]),
        ApiResponse(responseCode = "400", description = "Malformed, unsupported or inconsistent package; nothing imported", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "409", description = "POINT_RESULT_CLIENT_REQUIRED, POINT_RESULT_WRITE_DISABLED, POINT_SUBSTITUTION_WRITE_DISABLED, POINT_LINEUP_WRITE_DISABLED, ANNOUNCEMENT_WRITE_DISABLED or POSSESSION_CHANGE_WRITE_DISABLED for included content", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    ])
    fun importPackage(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferPackage::class), examples = [ExampleObject(name = "portableMatch", value = MatchTransferExamples.PACKAGE), ExampleObject(name = "pointSubstitution", value = MatchTransferExamples.SUBSTITUTION_PACKAGE), ExampleObject(name = "incomingReserve", value = MatchTransferExamples.INCOMING_RESERVE_PACKAGE), ExampleObject(name = "pointResult", value = MatchTransferExamples.POINT_RESULT_PACKAGE)])])
        @RequestBody body: String,
        request: HttpServletRequest,
    ): MatchTransferResult = service.importPackage(decode(body, MatchTransferPackage::class.java), request.pointLineupClient())

    private fun <T> decode(body: String, type: Class<T>): T = try {
        reader.readValue(body, type) ?: throw InvalidMatchTransferException("body", "JSON object is required")
    } catch (error: JsonProcessingException) {
        val path = (error as? JsonMappingException)?.path?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
            ?.takeIf { it.isNotEmpty() } ?: "body"
        throw InvalidMatchTransferException(path, "Malformed JSON, missing field or unsupported value")
    }

    @ExceptionHandler(InvalidMatchTransferException::class)
    fun invalid(error: InvalidMatchTransferException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid match transfer", error.message.orEmpty(), request)

    @ExceptionHandler(ExportMatchNotFoundException::class)
    fun missing(error: ExportMatchNotFoundException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", error.message.orEmpty(), request)

    @ExceptionHandler(ExportPointLineupUnsupportedException::class)
    fun lineupUnsupported(error: ExportPointLineupUnsupportedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, "POINT_LINEUP_EXPORT_UNSUPPORTED", "Point lineup export unsupported", error.message.orEmpty(), request)

    @ExceptionHandler(TransferWriteDisabledException::class)
    fun writesDisabled(error: TransferWriteDisabledException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.CONFLICT, error.problem.code.name, error.problem.title, error.problem.detail, request)

    private fun problem(status: HttpStatus, code: String, title: String, detail: String, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        problem.instance = URI.create(request.requestURI)
        problem.setProperty("code", code)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
}
