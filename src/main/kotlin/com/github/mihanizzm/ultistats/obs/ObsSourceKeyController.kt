package com.github.mihanizzm.ultistats.obs

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class IssueObsSourceKeyRequest(val matchId: UUID)

@RestController
@RequestMapping("/api/v1/obs/source-keys")
@SecurityRequirement(name = "staffSession")
class ObsSourceKeyController(private val service: ObsSourceKeyService) {
    @GetMapping
    @Operation(summary = "List OBS source key metadata", description = "Requires OBS_ADMIN. No-store.")
    fun list() = service.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Issue a key for an exact scoreboard",
        description = "Requires OBS_ADMIN, CSRF and captured installation, account and session IDs. No-store. " +
            "The secret is returned once. Other keys stay active.",
    )
    @ApiResponse(responseCode = "201", description = "Key issued", useReturnTypeSchema = true)
    fun issue(@RequestBody request: IssueObsSourceKeyRequest) = service.issue(request.matchId)

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
