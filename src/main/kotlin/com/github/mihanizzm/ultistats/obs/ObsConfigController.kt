package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.exception.EntityNotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/obs/config")
@SecurityRequirement(name = "staffSession")
class ObsConfigController(private val service: ObsConfigService) {
    @GetMapping
    @Operation(summary = "Прочитать общие настройки OBS", description = "OBS_ADMIN staff read. Один набор для всего сервиса. GET не изменяет журнал игры.")
    fun get() = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get())

    @ExceptionHandler(EntityNotFoundException::class)
    fun missing(cause: EntityNotFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, cause.message))

    @PutMapping
    @Operation(summary = "Заменить общие настройки OBS", description = "Последнее сохранение побеждает. 1–20 строк, 1–60 секунд, цвета #RRGGBB, до 500 UUID команд. matchId заменяет цвета только двух команд матча. preserveTeamColors=true сохраняет текущие цвета при записи общих настроек без матча. После commit — config-changed. OBS_ADMIN; существующие captured session и CSRF для записи.")
    fun put(
        @Valid @RequestBody config: ObsConfig,
        @RequestParam(required = false) matchId: UUID?,
        @RequestParam(defaultValue = "false") preserveTeamColors: Boolean,
    ) = service.save(config, matchId, preserveTeamColors)

    @GetMapping("/stream")
    @Operation(summary = "Retired OBS config stream", description = "OBS_ADMIN only. Always 410; never opens an event subscription. Use protected source links or captured staff previews.", deprecated = true)
    @ApiResponse(responseCode = "410", description = "Retired; no event channel")
    fun stream() = retiredObsStream()
}
