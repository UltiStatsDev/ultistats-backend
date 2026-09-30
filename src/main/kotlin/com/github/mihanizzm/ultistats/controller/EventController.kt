package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.request.CreateEventRequest
import com.github.mihanizzm.ultistats.dto.request.UpdateEventRequest
import com.github.mihanizzm.ultistats.dto.response.EventResponse
import com.github.mihanizzm.ultistats.facade.EventFacade
import com.github.mihanizzm.ultistats.facade.EventResult
import com.github.mihanizzm.ultistats.model.events.Event
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/matches/{matchId}/events")
@Tag(name = "Events", description = "Управление событиями матча")
class EventController(
    private val eventFacade: EventFacade,
) {
    @GetMapping
    @Operation(summary = "Получить все события матча")
    fun getAll(@PathVariable matchId: UUID): ResponseEntity<List<EventResponse>> =
        when (val result = eventFacade.getAll(matchId)) {
            is EventResult.EventList -> ResponseEntity.ok(result.events)
            else -> ResponseEntity.notFound().build()
        }

    @GetMapping("/{eventId}")
    @Operation(summary = "Получить событие по ID")
    fun get(
        @PathVariable matchId: UUID,
        @PathVariable eventId: UUID,
    ): ResponseEntity<EventResponse> = when (val result = eventFacade.get(matchId, eventId)) {
        is EventResult.Success -> ResponseEntity.ok(result.response)
        else -> ResponseEntity.notFound().build()
    }

    @PostMapping
    @Operation(summary = "Создать событие")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "400",
                description = "Некорректное тело запроса или недопустимые участники события",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun create(
        @PathVariable matchId: UUID,
        @OpenApiRequestBody(
            required = true,
            description = """Создание события матча. Поле `type` обязательно и определяет остальные поля запроса.""",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CreateEventRequest::class),
                examples = [
                    ExampleObject(
                        name = "onePlayerEvent",
                        summary = "Событие с одним игроком",
                        description = "Допустимые type: INCOMPLETE_PASS, PULL, BRICK, PICKUP.",
                        value = """{"type":"PICKUP","occurredAt":"2026-07-28T12:30:00Z","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "twoPlayerEvent",
                        summary = "Событие с двумя игроками",
                        description = "Допустимые type: PASS, GOAL, BLOCK, BLOCK_MARKER, BLOCK_FIELD, INTERCEPTION, CALLAHAN.",
                        value = """{"type":"PASS","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"22222222-2222-2222-2222-222222222222"}""",
                    ),
                    ExampleObject(
                        name = "teamEvent",
                        summary = "Командное событие",
                        description = "Допустимые type: TIMEOUT_START, TIMEOUT_END.",
                        value = """{"type":"TIMEOUT_START","occurredAt":"2026-07-28T12:30:00Z","teamId":"33333333-3333-3333-3333-333333333333"}""",
                    ),
                    ExampleObject(
                        name = "systemEvent",
                        summary = "Системное событие",
                        description = "Допустимые type: HALFTIME_START, HALFTIME_END.",
                        value = """{"type":"HALFTIME_START","occurredAt":"2026-07-28T12:30:00Z"}""",
                    ),
                ],
            )],
        )
        @RequestBody request: CreateEventRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        when (val result = eventFacade.create(matchId, request)) {
            is EventResult.Success -> ResponseEntity.status(HttpStatus.CREATED).body(result.response)
            is EventResult.NotFound -> notFound(matchId, null, servletRequest)
            is EventResult.BadRequest -> badRequest(result.problem, servletRequest)
            is EventResult.InvalidState -> conflict(result.problem, servletRequest)
            is EventResult.Conflict -> conflict(result.problem, servletRequest)
            else -> ResponseEntity.internalServerError().build<Any>()
        }

    @PatchMapping("/{eventId}")
    @Operation(summary = "Исправить участников события")
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "400",
                description = "Недопустимое изменение события",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun update(
        @PathVariable matchId: UUID,
        @PathVariable eventId: UUID,
        @RequestBody request: UpdateEventRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        when (val result = eventFacade.edit(matchId, eventId, request)) {
            is EventResult.Success -> ResponseEntity.ok(result.response)
            is EventResult.NotFound -> notFound(matchId, eventId, servletRequest)
            is EventResult.BadRequest -> badRequest(result.problem, servletRequest)
            is EventResult.MethodNotAllowed -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build<Any>()
            is EventResult.InvalidState -> conflict(result.problem, servletRequest)
            is EventResult.Conflict -> conflict(result.problem, servletRequest)
            else -> ResponseEntity.internalServerError().build<Any>()
        }

    @DeleteMapping("/{eventId}")
    @Operation(summary = "Удалить событие по ID")
    fun delete(
        @PathVariable matchId: UUID,
        @PathVariable eventId: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> =
        when (val result = eventFacade.delete(matchId, eventId)) {
            is EventResult.Deleted -> ResponseEntity.noContent().build<Any>()
            is EventResult.NotFound -> notFound(matchId, eventId, servletRequest)
            is EventResult.InvalidState -> conflict(result.problem, servletRequest)
            is EventResult.Conflict -> conflict(result.problem, servletRequest)
            else -> ResponseEntity.internalServerError().build<Any>()
        }

    private fun notFound(matchId: UUID, eventId: UUID?, request: HttpServletRequest): ResponseEntity<*> {
        val detail = if (eventId == null) "Match $matchId not found"
        else "Match $matchId or event $eventId not found"
        val problem = MatchProblem(MatchProblemCode.RESOURCE_NOT_FOUND, "Resource not found", detail)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(problem.toProblemDetail(HttpStatus.NOT_FOUND, URI.create(request.requestURI)))
    }

    private fun badRequest(problem: MatchProblem, request: HttpServletRequest): ResponseEntity<*> {
        return ResponseEntity.badRequest()
            .body(problem.toProblemDetail(HttpStatus.BAD_REQUEST, URI.create(request.requestURI)))
    }

    private fun conflict(problem: MatchProblem, request: HttpServletRequest): ResponseEntity<*> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem.toProblemDetail(HttpStatus.CONFLICT, URI.create(request.requestURI)))
}
