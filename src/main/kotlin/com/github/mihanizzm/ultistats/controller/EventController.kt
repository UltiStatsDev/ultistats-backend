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
    @Operation(
        summary = "Создать событие",
        description = EventOpenApiDocumentation.CREATE_DESCRIPTION,
    )
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
            description = "Выберите пример нужного EventType. Поле `type` определяет обязательные поля payload.",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CreateEventRequest::class),
                examples = [
                    ExampleObject(
                        name = "PASS",
                        summary = "PASS — успешный пас",
                        description = "fromParticipantId — бросающий, toParticipantId — принимающий из той же команды.",
                        value = EventOpenApiDocumentation.PASS_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "GOAL",
                        summary = "GOAL — голевой пас",
                        description = "fromParticipantId — ассистирующий, toParticipantId — забивший из той же команды.",
                        value = EventOpenApiDocumentation.GOAL_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "INCOMPLETE_PASS",
                        summary = "INCOMPLETE_PASS — незавершённый пас",
                        description = "participantId — игрок, бросок которого не был доставлен партнёру.",
                        value = EventOpenApiDocumentation.INCOMPLETE_PASS_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "PULL",
                        summary = "PULL — ввод диска в игру",
                        description = "participantId — игрок, выполнивший пулл.",
                        value = EventOpenApiDocumentation.PULL_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "BRICK",
                        summary = "BRICK — объявленный brick",
                        description = "participantId — игрок, выполнивший предшествующий пулл.",
                        value = EventOpenApiDocumentation.BRICK_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "PICKUP",
                        summary = "PICKUP — подбор свободного диска",
                        description = "participantId — игрок, который подбирает диск и становится его владельцем.",
                        value = EventOpenApiDocumentation.PICKUP_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "BLOCK",
                        summary = "BLOCK — блок без уточнения",
                        description = "fromParticipantId — бросающий, toParticipantId — защитник из другой команды.",
                        value = EventOpenApiDocumentation.BLOCK_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "BLOCK_MARKER",
                        summary = "BLOCK_MARKER — блок маркером",
                        description = "fromParticipantId — бросающий, toParticipantId — маркировавший его защитник.",
                        value = EventOpenApiDocumentation.BLOCK_MARKER_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "BLOCK_FIELD",
                        summary = "BLOCK_FIELD — полевой блок",
                        description = "fromParticipantId — бросающий, toParticipantId — полевой защитник.",
                        value = EventOpenApiDocumentation.BLOCK_FIELD_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "INTERCEPTION",
                        summary = "INTERCEPTION — перехват",
                        description = "fromParticipantId — бросающий, toParticipantId — перехвативший защитник из другой команды.",
                        value = EventOpenApiDocumentation.INTERCEPTION_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "CALLAHAN",
                        summary = "CALLAHAN — перехват с голом",
                        description = "fromParticipantId — бросающий, toParticipantId — защитник, выполнивший Callahan.",
                        value = EventOpenApiDocumentation.CALLAHAN_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "TIMEOUT_START",
                        summary = "TIMEOUT_START — начало таймаута",
                        description = "teamId — команда, которая начинает таймаут.",
                        value = EventOpenApiDocumentation.TIMEOUT_START_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "TIMEOUT_END",
                        summary = "TIMEOUT_END — окончание таймаута",
                        description = "teamId — команда, таймаут которой завершается.",
                        value = EventOpenApiDocumentation.TIMEOUT_END_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "HALFTIME_START",
                        summary = "HALFTIME_START — начало перерыва",
                        description = "Системное событие без идентификаторов игроков или команд.",
                        value = EventOpenApiDocumentation.HALFTIME_START_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "HALFTIME_END",
                        summary = "HALFTIME_END — окончание перерыва",
                        description = "Системное событие без идентификаторов игроков или команд.",
                        value = EventOpenApiDocumentation.HALFTIME_END_EXAMPLE,
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
    @Operation(
        summary = "Исправить участников события",
        description = EventOpenApiDocumentation.UPDATE_DESCRIPTION,
    )
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
        @OpenApiRequestBody(
            required = true,
            description = "Payload зависит от категории уже сохранённого события.",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = UpdateEventRequest::class),
                examples = [
                    ExampleObject(
                        name = "onePlayerEvent",
                        summary = "Исправить участника PICKUP",
                        description = "participantId обязателен для события с одним участником.",
                        value = EventOpenApiDocumentation.PICKUP_PATCH_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "twoPlayerEvent",
                        summary = "Исправить принимающего в PASS",
                        description = "У события с двумя участниками разрешено передать только изменяемый ID.",
                        value = EventOpenApiDocumentation.PASS_PATCH_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "blockTypeCorrection",
                        summary = "Уточнить вид блока",
                        description = "Типы BLOCK, BLOCK_MARKER и BLOCK_FIELD можно заменять друг на друга.",
                        value = EventOpenApiDocumentation.BLOCK_PATCH_EXAMPLE,
                    ),
                    ExampleObject(
                        name = "teamEvent",
                        summary = "Исправить команду таймаута",
                        description = "teamId обязателен для события таймаута.",
                        value = EventOpenApiDocumentation.TIMEOUT_PATCH_EXAMPLE,
                    ),
                ],
            )],
        )
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
