package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/infographics/leaders")
@SecurityRequirement(name = "staffSession")
@ApiResponse(responseCode = "200", description = "Свежие лидеры каждой команды; все равные, no-store. AUTO snapshot может быть null", useReturnTypeSchema = true)
@ApiResponse(responseCode = "401", description = "Нет действующей staff session")
@ApiResponse(responseCode = "403", description = "Требуется OBS_ADMIN и HTTPS; viewer bearer не даёт доступа")
@ApiResponse(responseCode = "404", description = "Матч удалён или не найден")
class ObsInfographicsLeadersController(private val leaders: ObsInfographicsLeadersService) {
    @GetMapping("/{matchId}")
    @Operation(summary = "Прочитать текущих идентифицированных лидеров матча, без команд эфира")
    fun exact(@PathVariable matchId: String) = leaders.exact(sourceUuid(matchId))

    @GetMapping("/auto")
    @Operation(summary = "Прочитать лидеров текущего AUTO матча; пустой выбор даёт snapshot:null")
    fun auto() = leaders.auto()
}
