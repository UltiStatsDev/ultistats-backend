package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/infographics/presentations")
@SecurityRequirement(name = "staffSession")
@ApiResponse(responseCode = "400", description = "Недопустимые поля команды или поколение")
@ApiResponse(responseCode = "401", description = "Нет действующей staff session")
@ApiResponse(responseCode = "403", description = "Требуются OBS_ADMIN, HTTPS, текущая сессия и CSRF")
@ApiResponse(responseCode = "409", description = "Изменился контекст, ревизия или поколение, конфликт operationId либо запрошенный показ недоступен")
class ObsInfographicsPresentationController(private val presentations: ObsInfographicsPresentationService) {
    @PostMapping
    @ApiResponse(responseCode = "200", description = "Зафиксированный ответ команды показа", useReturnTypeSchema = true)
    @Operation(summary = "Показать лидеров или итоги матча", description = "kind LEADERS (по умолчанию) или STATS. EXACT/AUTO с захваченными матчем, ревизиями и поколением. Повтор той же операции возвращает исходные времена и не продлевает показ.")
    fun show(@RequestBody request: ObsPresentationRequest) = presentations.show(request)

    @DeleteMapping("/{generation}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Скрыть точное поколение показа", description = "Старое поколение не может скрыть новый показ.")
    fun hide(@PathVariable generation: String) = presentations.hide(sourceUuid(generation))
}
