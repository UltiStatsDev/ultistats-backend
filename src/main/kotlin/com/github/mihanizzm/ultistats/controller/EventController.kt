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
        description = "Добавляет одно событие в журнал матча IN_PROGRESS. Обязательны type и occurredAt, " +
            "а остальные поля зависят от type (см. отдельный пример каждого типа). " +
            "participantId/fromParticipantId/toParticipantId — идентификаторы участников из состава этого матча, " +
            "включая UNKNOWN; используйте participantId из ответа GET /api/v1/matches/{matchId}. " +
            "Для PASS/GOAL нужны разные участники одной команды, для блоков и перехватов — противоположных команд. " +
            "UUID в примерах условные: замените их идентификаторами своего матча. " +
            "occurredAt передаётся в ISO-8601 UTC, не раньше начала матча и последнего активного события; " +
            "одинаковое время допустимо. Недопустимая последовательность событий возвращает 409.",
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
            description = """Создание события матча. Поле `type` обязательно и определяет остальные поля запроса.""",
            content = [Content(
                mediaType = "application/json",
                schema = Schema(implementation = CreateEventRequest::class),
                examples = [
                    ExampleObject(
                        name = "PASS",
                        summary = "PASS — Успешный пас",
                        description = "Пас партнёру: fromParticipantId — бросающий, toParticipantId — принимающий. Участники одной команды; принимающий становится владельцем диска.",
                        value = """{"type":"PASS","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"22222222-2222-2222-2222-222222222222"}""",
                    ),
                    ExampleObject(
                        name = "GOAL",
                        summary = "GOAL — Гол с ассистом",
                        description = "Голевой пас: fromParticipantId — автор ассиста, toParticipantId — поймавший диск в голевой зоне и забивший гол. Участники одной команды; розыгрыш завершается.",
                        value = """{"type":"GOAL","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"22222222-2222-2222-2222-222222222222"}""",
                    ),
                    ExampleObject(
                        name = "INCOMPLETE_PASS",
                        summary = "INCOMPLETE_PASS — Незавершённый пас",
                        description = "Пас без зафиксированного действия защиты: выброс, аут или неудачная ловля. participantId — бросающий, а не не поймавший игрок. Диск свободен до PICKUP.",
                        value = """{"type":"INCOMPLETE_PASS","occurredAt":"2026-07-28T12:30:00Z","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "PULL",
                        summary = "PULL — Ввод диска в игру",
                        description = "Пулл в начале матча, после гола или перерыва. participantId — выполнивший пулл игрок. Владение не устанавливается; далее ожидается BRICK или PICKUP.",
                        value = """{"type":"PULL","occurredAt":"2026-07-28T12:30:00Z","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "BRICK",
                        summary = "BRICK — Брик после пулла",
                        description = "Объявление брика непосредственно после PULL. participantId — игрок, выполнивший предшествующий пулл, а не объявивший брик. У него увеличивается статистика bricks; далее требуется PICKUP.",
                        value = """{"type":"BRICK","occurredAt":"2026-07-28T12:30:00Z","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "PICKUP",
                        summary = "PICKUP — Подбор свободного диска",
                        description = "participantId — подобравший диск участник. Применяется после PULL, BRICK, INCOMPLETE_PASS или блока; начинает новое владение.",
                        value = """{"type":"PICKUP","occurredAt":"2026-07-28T12:30:00Z","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "BLOCK",
                        summary = "BLOCK — Блок без уточнения позиции",
                        description = "fromParticipantId — бросающий, toParticipantId — блокирующий защитник противоположной команды. Позиция защитника не уточнена. Диск свободен до PICKUP.",
                        value = """{"type":"BLOCK","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"44444444-4444-4444-4444-444444444444"}""",
                    ),
                    ExampleObject(
                        name = "BLOCK_MARKER",
                        summary = "BLOCK_MARKER — Блок на маркере",
                        description = "fromParticipantId — бросающий, toParticipantId — маркировавший его защитник противоположной команды. Блок учитывается в marker-статистике; далее требуется PICKUP.",
                        value = """{"type":"BLOCK_MARKER","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"44444444-4444-4444-4444-444444444444"}""",
                    ),
                    ExampleObject(
                        name = "BLOCK_FIELD",
                        summary = "BLOCK_FIELD — Полевой блок",
                        description = "fromParticipantId — бросающий, toParticipantId — полевой защитник противоположной команды, выполнивший блок. Блок учитывается в field-статистике; далее требуется PICKUP.",
                        value = """{"type":"BLOCK_FIELD","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"44444444-4444-4444-4444-444444444444"}""",
                    ),
                    ExampleObject(
                        name = "INTERCEPTION",
                        summary = "INTERCEPTION — Перехват с владением",
                        description = "fromParticipantId — бросающий соперник, toParticipantId — поймавший его пас защитник противоположной команды. Защитник сразу получает владение, дополнительный PICKUP не нужен.",
                        value = """{"type":"INTERCEPTION","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"44444444-4444-4444-4444-444444444444"}""",
                    ),
                    ExampleObject(
                        name = "CALLAHAN",
                        summary = "CALLAHAN — Перехват с голом",
                        description = "fromParticipantId — бросающий соперник, toParticipantId — защитник противоположной команды, перехвативший диск в атакуемой им голевой зоне. Засчитываются перехват и гол; розыгрыш завершается.",
                        value = """{"type":"CALLAHAN","occurredAt":"2026-07-28T12:30:00Z","fromParticipantId":"11111111-1111-1111-1111-111111111111","toParticipantId":"44444444-4444-4444-4444-444444444444"}""",
                    ),
                    ExampleObject(
                        name = "TIMEOUT_START",
                        summary = "TIMEOUT_START — Начало таймаута",
                        description = "teamId — команда-участница матча, взявшая таймаут. Игровой таймер останавливается, предыдущее состояние сохраняется до TIMEOUT_END.",
                        value = """{"type":"TIMEOUT_START","occurredAt":"2026-07-28T12:30:00Z","teamId":"33333333-3333-3333-3333-333333333333"}""",
                    ),
                    ExampleObject(
                        name = "TIMEOUT_END",
                        summary = "TIMEOUT_END — Окончание таймаута",
                        description = "teamId — команда, чей таймаут заканчивается. После TIMEOUT_START возобновляются таймер и предыдущее игровое состояние.",
                        value = """{"type":"TIMEOUT_END","occurredAt":"2026-07-28T12:30:00Z","teamId":"33333333-3333-3333-3333-333333333333"}""",
                    ),
                    ExampleObject(
                        name = "HALFTIME_START",
                        summary = "HALFTIME_START — Начало перерыва",
                        description = "Начинает перерыв после завершённого розыгрыша и останавливает игровой таймер. Требуются только type и occurredAt, без participantId/teamId.",
                        value = """{"type":"HALFTIME_START","occurredAt":"2026-07-28T12:30:00Z"}""",
                    ),
                    ExampleObject(
                        name = "HALFTIME_END",
                        summary = "HALFTIME_END — Окончание перерыва",
                        description = "Завершает перерыв после HALFTIME_START. Далее ожидается PULL нового розыгрыша. Требуются только type и occurredAt, без participantId/teamId.",
                        value = """{"type":"HALFTIME_END","occurredAt":"2026-07-28T12:30:00Z"}""",
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
        summary = "Исправить участников, команду или разновидность блока",
        description = "Обязательное поле type должно совпадать с сохранённым типом события. " +
            "Исключение: разрешены взаимные замены BLOCK, BLOCK_MARKER и BLOCK_FIELD; другие смены дают 400. " +
            "Для события с одним участником обязателен participantId, для командного — teamId. " +
            "В событии с двумя участниками fromParticipantId и toParticipantId необязательны: " +
            "отсутствие или null сохраняет прежнее значение; можно передать только type для уточнения блока. " +
            "Роли участников и ограничения команд совпадают с POST. " +
            "occurredAt неизменяем и не входит в PATCH; идентификатор и sequenceNumber также сохраняются. " +
            "Системные события HALFTIME_START и HALFTIME_END не редактируются: " +
            "PATCH с их сохранённым type возвращает 405 Method Not Allowed. " +
            "Исправление допускается после начала матча, в том числе после завершения; " +
            "нарушение последовательности событий или правил состояния возвращает 409.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Исправленное событие с прежними id, sequenceNumber и occurredAt",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = EventResponse::class),
                )],
            ),
            ApiResponse(responseCode = "405", description = "Редактирование системного события не поддерживается", content = []),
            ApiResponse(
                responseCode = "409",
                description = "Конфликт состояния матча или последовательности событий",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
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
            description = "Исправление существующего события без изменения его времени.",
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = UpdateEventRequest::class),
                examples = [
                    ExampleObject(
                        name = "onePlayerEvent",
                        summary = "Исправить выполнившего пулл",
                        description = "Для сохранённого PULL заменяет participantId; оба поля обязательны.",
                        value = """{"type":"PULL","participantId":"11111111-1111-1111-1111-111111111111"}""",
                    ),
                    ExampleObject(
                        name = "twoPlayerEvent",
                        summary = "Исправить только принимающего пас",
                        description = "Для сохранённого PASS заменяет toParticipantId; прежний бросающий сохраняется. Участники должны остаться разными игроками одной команды.",
                        value = """{"type":"PASS","toParticipantId":"22222222-2222-2222-2222-222222222222"}""",
                    ),
                    ExampleObject(
                        name = "blockRefinement",
                        summary = "Уточнить блок как блок на маркере",
                        description = "Для BLOCK или BLOCK_FIELD меняет type на BLOCK_MARKER, сохраняя обоих участников.",
                        value = """{"type":"BLOCK_MARKER"}""",
                    ),
                    ExampleObject(
                        name = "teamEvent",
                        summary = "Исправить команду таймаута",
                        description = "Для сохранённого TIMEOUT_START заменяет teamId на другую команду-участницу матча; оба поля обязательны.",
                        value = """{"type":"TIMEOUT_START","teamId":"33333333-3333-3333-3333-333333333333"}""",
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

    @PostMapping("/undo")
    @Operation(
        summary = "Отменить последнее событие матча",
        description = """Без тела запроса. Мягко удаляет активное событие с наибольшим sequenceNumber
            под блокировкой матча, пересчитывает счёт и после commit отправляет SSE event-log-changed
            с operation=DELETED. Возвращает отменённое событие. Сохраняет правила удаления событий:
            журнал завершённого матча должен оканчиваться завершённым розыгрышем.
            Операция неидемпотентна: каждый повторный вызов отменяет следующее активное событие.
            Не повторяйте запрос автоматически при потере ответа; сначала перечитайте журнал.""",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Отменённое событие",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = EventResponse::class),
                )],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Матч не найден или активных событий нет",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
            ApiResponse(
                responseCode = "409",
                description = "Отмена нарушает состояние матча или последовательность событий",
                content = [Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = Schema(implementation = ProblemDetail::class),
                )],
            ),
        ],
    )
    fun undoLast(
        @PathVariable matchId: UUID,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<*> = when (val result = eventFacade.undoLast(matchId)) {
        is EventResult.Success -> ResponseEntity.ok(result.response)
        is EventResult.NotFound -> notFound("Match $matchId not found or has no active events", servletRequest)
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
        return notFound(detail, request)
    }

    private fun notFound(detail: String, request: HttpServletRequest): ResponseEntity<*> {
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
