package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.csvimport.*
import com.github.mihanizzm.ultistats.service.CsvImportResult
import com.github.mihanizzm.ultistats.service.CsvImportService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import org.springframework.core.env.Environment
import org.springframework.http.*
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartHttpServletRequest

class CsvImportUpload {
    @field:Schema(type = "string", format = "binary", requiredMode = Schema.RequiredMode.REQUIRED)
    lateinit var applications: String
    @field:Schema(type = "string", format = "binary", requiredMode = Schema.RequiredMode.REQUIRED)
    lateinit var rosters: String
}

data class CsvImportProblem(val type: String, val title: String, val status: Int, val detail: String,
    val errors: List<CsvImportError>)

@RestController
@RequestMapping("/api/v1/imports/teams-players/csv")
class CsvImportController(private val parser: CsvImportParser, private val service: CsvImportService,
    private val environment: Environment) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Создать новые команды и игроков из двух CSV", requestBody = OpenApiRequestBody(
        required = true, content = [Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            schema = Schema(implementation = CsvImportUpload::class))]))
    @ApiResponse(responseCode = "201", description = "Новые UUID, количества и связи; транзакция завершена", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Ошибки CSV или multipart; errors: file, row, column, code",
        content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = CsvImportProblem::class))])
    @ApiResponse(responseCode = "413", description = "Превышен лимит файла или записей",
        content = [Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = Schema(implementation = CsvImportProblem::class))])
    @ApiResponse(responseCode = "404", description = "Импорт выключен")
    fun create(request: MultipartHttpServletRequest): ResponseEntity<CsvImportResult> {
        if (!environment.getProperty("ultistats.csv-import.enabled", Boolean::class.java, false)) return ResponseEntity.notFound().build()
        val expected = setOf("applications", "rosters")
        val parts = request.parts
        if (parts.size != 2 || parts.map { it.name }.toSet() != expected || parts.any { it.submittedFileName == null })
            reject("applications", code = "MULTIPART_PARTS")
        val files = expected.associateWith { name ->
            val part = parts.single { it.name == name }
            if (part.size > CSV_FILE_LIMIT) reject(name, code = "FILE_TOO_LARGE", status = HttpStatus.PAYLOAD_TOO_LARGE)
            val bytes = part.inputStream.use { it.readNBytes(CSV_FILE_LIMIT + 1) }
            if (bytes.size > CSV_FILE_LIMIT) reject(name, code = "FILE_TOO_LARGE", status = HttpStatus.PAYLOAD_TOO_LARGE)
            bytes
        }
        // Two independently bounded files imply the 2 MiB total bound too.
        val batch = parser.parse(files.getValue("applications"), files.getValue("rosters"))
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(batch))
    }

    @ExceptionHandler(CsvImportException::class)
    fun invalid(error: CsvImportException): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(error.status, "CSV import rejected; see errors")
        problem.setProperty("errors", error.errors)
        return ResponseEntity.status(error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }
}
