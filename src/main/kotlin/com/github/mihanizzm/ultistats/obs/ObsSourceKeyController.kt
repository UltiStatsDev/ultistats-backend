package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RotateObsSourceKeyRequest(
    val matchId: UUID,
    val operationId: UUID,
    @field:Schema(defaultValue = "EXACT_SCOREBOARD")
    val scope: ObsSourceKeyScope = ObsSourceKeyScope.EXACT_SCOREBOARD,
)

@RestController
@RequestMapping("/api/v1/obs/source-keys")
@SecurityRequirement(name = "staffSession")
class ObsSourceKeyController(private val service: ObsSourceKeyService) {
    @GetMapping
    @Operation(summary = "List OBS source key metadata", description = "Requires OBS_ADMIN. No-store.")
    fun list() = service.list()

    @GetMapping("/current/{matchId}")
    @Operation(
        summary = "Copy the current exact source link",
        description = "HTTPS and OBS_ADMIN. No-store. Defaults to EXACT_SCOREBOARD. Legacy digest-only keys report REISSUE_REQUIRED.",
    )
    @ApiResponse(
        responseCode = "200", description = "ACTIVE, REISSUE_REQUIRED or NO_ACTIVE_LINK", useReturnTypeSchema = true,
        headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))],
    )
    @ApiResponse(responseCode = "400", description = "Invalid match ID or scope", content = [Content()])
    @ApiResponse(responseCode = "401", description = "Staff session required", content = [Content()])
    @ApiResponse(responseCode = "403", description = "OBS_ADMIN on HTTPS required", content = [Content()])
    @ApiResponse(responseCode = "404", description = "OBS_SOURCE_MATCH_NOT_FOUND", content = [Content()])
    @ApiResponse(
        responseCode = "503", description = "OBS_SOURCE_KEY_MATERIAL_UNAVAILABLE",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    fun current(
        @Parameter(schema = Schema(type = "string", format = "uuid")) @PathVariable matchId: String,
        @RequestParam(defaultValue = "EXACT_SCOREBOARD") scope: ObsSourceKeyScope,
    ) = service.current(sourceUuid(matchId), scope)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Rotate the exact source link",
        description = "HTTPS, OBS_ADMIN, CSRF and captured installation, account and session IDs. No-store. " +
            "Defaults to EXACT_SCOREBOARD. Atomically revokes the previous key for this match and scope. " +
            "A retry of the current operation returns the same link.",
    )
    @ApiResponse(
        responseCode = "201", description = "New or replayed current link", useReturnTypeSchema = true,
        headers = [Header(name = "Cache-Control", schema = Schema(type = "string", allowableValues = ["no-store"]))],
    )
    @ApiResponse(responseCode = "400", description = "Both UUID fields are required and scope must be supported", content = [Content()])
    @ApiResponse(responseCode = "401", description = "Staff session required", content = [Content()])
    @ApiResponse(responseCode = "403", description = "OBS_ADMIN on HTTPS, CSRF and identity required", content = [Content()])
    @ApiResponse(responseCode = "404", description = "OBS_SOURCE_MATCH_NOT_FOUND", content = [Content()])
    @ApiResponse(
        responseCode = "409", description = "OBS_SOURCE_OPERATION_CONFLICT for a retired or differently scoped operation ID",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "503", description = "OBS_SOURCE_KEY_MATERIAL_UNAVAILABLE or OBS_SOURCE_ROTATION_UNAVAILABLE",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))],
    )
    fun rotate(@RequestBody request: RotateObsSourceKeyRequest) = service.rotate(request.matchId, request.operationId, request.scope)

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Revoke an OBS source key",
        description = "Requires OBS_ADMIN, CSRF and captured installation, account and session IDs. No-store. " +
            "Revoking the same known key again succeeds.",
    )
    fun revoke(
        @Parameter(schema = Schema(type = "string", format = "uuid")) @PathVariable keyId: String,
    ) = service.revoke(sourceUuid(keyId))
}

internal fun sourceUuid(value: String): UUID =
    runCatching { UUID.fromString(value) }.getOrNull()
        ?.takeIf { it.toString().equals(value, ignoreCase = true) }
        ?: throw AuthProblem(400, "OBS_SOURCE_INVALID_ID")
