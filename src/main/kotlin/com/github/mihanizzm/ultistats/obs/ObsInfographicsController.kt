package com.github.mihanizzm.ultistats.obs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/obs/sources/infographics")
@SecurityRequirement(name = "obsSourceBearer")
@ApiResponse(responseCode = "200", description = "No-store match snapshot; AUTO wraps a nullable snapshot", useReturnTypeSchema = true)
@ApiResponse(responseCode = "401", description = "Missing, invalid or revoked bearer")
@ApiResponse(responseCode = "403", description = "Wrong source scope or insecure transport")
@ApiResponse(responseCode = "404", description = "Exact match removed or not found")
class ObsInfographicsController(private val service: ObsInfographicsService) {
    @GetMapping("/{matchId}")
    @Operation(summary = "Read the exact match infographic snapshot with an EXACT_INFOGRAPHICS bearer")
    fun exact(@PathVariable matchId: String, request: HttpServletRequest) =
        service.exact(sourceUuid(matchId), request.getHeaders(HttpHeaders.AUTHORIZATION).toList())

    @GetMapping("/auto")
    @Operation(summary = "Read the AUTO infographic snapshot with an AUTO_INFOGRAPHICS bearer")
    fun auto(request: HttpServletRequest) = service.auto(request.getHeaders(HttpHeaders.AUTHORIZATION).toList())
}
