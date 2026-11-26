package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.PutPointCompletionDraftRequest
import com.github.mihanizzm.ultistats.dto.response.PointCompletionDraftResponse
import com.github.mihanizzm.ultistats.service.PointCompletionDraftRejected
import com.github.mihanizzm.ultistats.service.PointCompletionDraftService
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches/{matchId}/point-lineups/{pointId}/completion-draft", produces = [MediaType.APPLICATION_JSON_VALUE])
class PointCompletionDraftController(private val drafts: PointCompletionDraftService) {
    @GetMapping
    @Operation(summary = "Read a finished point's completion draft and diagnostics",
        description = "Requires SPORTS_WRITE and capability 4. The draft revision starts at zero.",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER, required = true,
            schema = Schema(type = "string", allowableValues = ["4"]))])
    fun read(@PathVariable matchId: UUID, @PathVariable pointId: UUID, request: HttpServletRequest) =
        respond(drafts.read(matchId, pointId, request.pointLineupClient()))

    @PutMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PointResultCommand
    @Operation(summary = "Save an incomplete point's completion draft by revision",
        description = "Requires SPORTS_WRITE, CSRF, capability 4 and enabled point result writes. This changes the draft alone.",
        parameters = [Parameter(name = POINT_LINEUPS_HEADER, `in` = ParameterIn.HEADER, required = true,
            schema = Schema(type = "string", allowableValues = ["4"]))])
    fun put(@PathVariable matchId: UUID, @PathVariable pointId: UUID, @RequestBody body: PutPointCompletionDraftRequest,
            request: HttpServletRequest) = respond(drafts.save(matchId, pointId, body, request.pointLineupClient()))

    private fun respond(draft: PointCompletionDraftResponse?): ResponseEntity<PointCompletionDraftResponse> =
        if (draft == null) ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build()
        else ResponseEntity.ok().cacheControl(CacheControl.noStore()).varyBy(POINT_LINEUPS_HEADER).body(draft)

    @ExceptionHandler(PointCompletionDraftRejected::class)
    fun reject(error: PointCompletionDraftRejected, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = if (error.problem.code == MatchProblemCode.INVALID_REQUEST) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(error.problem.toProblemDetail(status, URI.create(request.requestURI)))
    }
}
