package com.github.mihanizzm.ultistats.obs

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// Documentation DTO only: runtime parsing deliberately rejects Jackson scalar coercion.
data class ObsInfographicsSettingsRequest(
    @field:Schema(type = "integer", minimum = "2", maximum = "15", defaultValue = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    val rosterPageSeconds: Int,
    @field:Schema(type = "integer", minimum = "2", maximum = "15", defaultValue = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    val pointPageSeconds: Int,
    @field:Schema(type = "integer", minimum = "2", maximum = "15", defaultValue = "5", requiredMode = Schema.RequiredMode.REQUIRED)
    val leaderPageSeconds: Int,
    @field:Schema(type = "integer", minimum = "1", defaultValue = "10", description = "Пропуск сохраняет текущий интервал.")
    val statsSeconds: Int? = null,
)

@RestController
@RequestMapping("/api/v1/obs/infographics/settings")
@SecurityRequirement(name = "staffSession")
class ObsInfographicsSettingsController(private val service: ObsInfographicsSettingsService) {
    @GetMapping
    @Operation(summary = "Прочитать независимые интервалы инфографики и ревизию")
    fun get() = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get())

    @PutMapping
    @Operation(summary = "Сохранить интервалы следующей фазы инфографики", description = "OBS_ADMIN, HTTPS, captured session и CSRF. Три интервала страниц обязательны: JSON integer 2..15, default5. Необязательный statsSeconds задаёт длительность следующего показа итогов: положительное целое, default10. Активная фаза сохраняет прежний интервал; новая ревизия инвалидирует команды лидеров. Повтор тех же значений сохраняет ревизию.")
    fun put(@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
        content = [Content(schema = Schema(implementation = ObsInfographicsSettingsRequest::class))])
        @RequestBody body: JsonNode) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(body))
}
