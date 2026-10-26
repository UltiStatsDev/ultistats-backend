package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.common.PageResponse
import com.github.mihanizzm.ultistats.dto.request.CreateMatchRequest
import com.github.mihanizzm.ultistats.dto.request.MatchFilterRequest
import com.github.mihanizzm.ultistats.dto.request.MatchSortRequest
import com.github.mihanizzm.ultistats.dto.request.MatchTimestampRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateMatchRequest
import com.github.mihanizzm.ultistats.dto.response.MatchListItemResponse
import com.github.mihanizzm.ultistats.dto.response.MatchResponse
import com.github.mihanizzm.ultistats.facade.MatchFacade
import com.github.mihanizzm.ultistats.model.MatchStatus
import com.github.mihanizzm.ultistats.service.result.MatchCommandResult
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches")
@Tag(name = "Matches", description = "Управление матчами")
class MatchController(
    private val matchFacade: MatchFacade,
) {
    @GetMapping
    @Operation(summary = "Получить матчи с пагинацией, фильтрацией и сортировкой")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Страница результатов", useReturnTypeSchema = true),
        ApiResponse(
            responseCode = "400", description = "Некорректные или неоднозначные параметры сортировки",
            content = [Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = Schema(implementation = ProblemDetail::class),
            )],
        ),
    ])
    fun getAll(
        @Parameter(description = "Номер страницы (начиная с 0)")
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "Размер страницы")
        @RequestParam(defaultValue = "20")
        size: Int,

        @Parameter(description = "Фильтр по ID команды-участника")
        @RequestParam(required = false)
        teamId: UUID?,

        @Parameter(description = "Фильтр по статусу матча (PLANNED, IN_PROGRESS, FINISHED)")
        @RequestParam(required = false)
        status: MatchStatus?,

        @Parameter(description = "Фильтр по дате начиная с (ISO 8601)")
        @RequestParam(required = false)
        dateFrom: Instant?,

        @Parameter(description = "Фильтр по дате до (ISO 8601)")
        @RequestParam(required = false)
        dateTo: Instant?,

        @ParameterObject @ModelAttribute
        sorting: MatchSortRequest,
        servletRequest: HttpServletRequest,
    ): PageResponse<MatchListItemResponse> {
        val filter = MatchFilterRequest(
            teamId = teamId,
            status = status,
            dateFrom = dateFrom,
            dateTo = dateTo,
        )
        return matchFacade.getAllPaged(page, size, filter, sorting.toSortParam(servletRequest.parameterMap))
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить матч по ID")
    fun getById(@PathVariable id: UUID): ResponseEntity<MatchResponse> =
        matchFacade.getById(id)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    @Operation(summary = "Создать матч")
    fun create(
        @RequestBody request: CreateMatchRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = matchFacade.create(request).toResponse(
        successStatus = HttpStatus.CREATED,
        instance = URI.create(servletRequest.requestURI),
        matchId = null,
    )

    @PutMapping("/{id}")
    @Operation(summary = "Обновить матч (частичное обновление)")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateMatchRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = matchFacade.update(id, request).toResponse(
        successStatus = HttpStatus.OK,
        instance = URI.create(servletRequest.requestURI),
        matchId = id,
    )

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить матч")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID): ResponseEntity<Unit> =
        if (matchFacade.delete(id)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @PostMapping("/{id}/start")
    @Operation(summary = "Начать матч")
    fun startMatch(
        @PathVariable id: UUID,
        @RequestBody request: MatchTimestampRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = matchFacade.startMatch(id, request).toResponse(
        successStatus = HttpStatus.OK,
        instance = URI.create(servletRequest.requestURI),
        matchId = id,
    )

    @PostMapping("/{id}/end")
    @Operation(summary = "Завершить матч")
    fun endMatch(
        @PathVariable id: UUID,
        @RequestBody request: MatchTimestampRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = matchFacade.endMatch(id, request).toResponse(
        successStatus = HttpStatus.OK,
        instance = URI.create(servletRequest.requestURI),
        matchId = id,
    )

    private fun MatchCommandResult<MatchResponse>.toResponse(
        successStatus: HttpStatus,
        instance: URI,
        matchId: UUID?,
    ): ResponseEntity<*> = when (this) {
        is MatchCommandResult.Success -> ResponseEntity.status(successStatus).body(value)
        MatchCommandResult.NotFound -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            notFoundProblem(requireNotNull(matchId)).toProblemDetail(HttpStatus.NOT_FOUND, instance),
        )
        is MatchCommandResult.InvalidRequest -> ResponseEntity.badRequest().body(problem.toProblemDetail(HttpStatus.BAD_REQUEST, instance))
        is MatchCommandResult.InvalidState -> ResponseEntity.status(HttpStatus.CONFLICT).body(problem.toProblemDetail(HttpStatus.CONFLICT, instance))
        is MatchCommandResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(problem.toProblemDetail(HttpStatus.CONFLICT, instance))
    }

    private fun notFoundProblem(matchId: UUID) = MatchProblem(
        code = MatchProblemCode.RESOURCE_NOT_FOUND,
        title = "Resource not found",
        detail = "Match $matchId not found",
    )
}
