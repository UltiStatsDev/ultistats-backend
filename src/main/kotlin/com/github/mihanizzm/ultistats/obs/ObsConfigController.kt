package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/obs/config")
class ObsConfigController(private val service: ObsConfigService, private val stream: ObsConfigStream) {
    @GetMapping
    @Operation(summary = "Прочитать общие настройки OBS", description = "Один набор для всего сервиса. GET не изменяет журнал игры.")
    fun get() = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get())

    @PutMapping
    @Operation(summary = "Заменить общие настройки OBS", description = "Последнее сохранение побеждает. 1–20 строк, 1–60 секунд, цвета #RRGGBB, до 500 UUID команд. После commit — config-changed. Доверенная сеть без авторизации.")
    fun put(@Valid @RequestBody config: ObsConfig) = service.save(config)

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "SSE общих настроек OBS", description = "connected/config-changed — перечитать GET. Process-local invalidation без durable replay; heartbeat 15 секунд.")
    fun stream() = ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(stream.subscribe())
}
