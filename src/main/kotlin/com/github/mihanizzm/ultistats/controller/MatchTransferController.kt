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
    @Operation(summary = "Export selected matches as portable JSON version 1",
        description = "Preserves selected order and historical snapshots. All statuses supported; missing/deleted matches return 404. Photos, current memberships, deleted events and idempotency metadata are excluded.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Portable package", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferPackage::class), examples = [ExampleObject(name = "portableMatch", value = MatchTransferExamples.PACKAGE)])]),
        ApiResponse(responseCode = "400", description = "Invalid selection", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
        ApiResponse(responseCode = "404", description = "A selected match does not exist or is deleted", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    ])
    fun export(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchExportRequest::class), examples = [ExampleObject(value = MatchTransferExamples.SELECTION)])])
        @RequestBody body: String,
    ): ResponseEntity<MatchTransferPackage> = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ultistats-matches.json\"")
        .body(service.export(decode(body, MatchExportRequest::class.java).matchIds))

    @PostMapping("/import", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Import a portable package as independent copies",
        description = "Only version 1 is accepted. New IDs are generated for every match/team/player/event and UNKNOWN participant; shared player/team identity is preserved within the package. Existing data is never overwritten. Repeat import creates another copy. Entire package is validated and committed atomically. Cards are active, without photos or current memberships. Status/timestamps/snapshots/event order are preserved and scores recalculated.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Source-to-created identity maps", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferResult::class), examples = [ExampleObject(value = MatchTransferExamples.RESULT)])]),
        ApiResponse(responseCode = "400", description = "Malformed, unsupported or inconsistent package; nothing imported", content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = ProblemDetail::class))]),
    ])
    fun importPackage(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = MatchTransferPackage::class), examples = [ExampleObject(name = "portableMatch", value = MatchTransferExamples.PACKAGE)])])
        @RequestBody body: String,
    ): MatchTransferResult = service.importPackage(decode(body, MatchTransferPackage::class.java))

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

    private fun problem(status: HttpStatus, code: String, title: String, detail: String, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        problem.instance = URI.create(request.requestURI)
        problem.setProperty("code", code)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
}
