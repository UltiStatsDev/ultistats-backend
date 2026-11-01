package com.github.mihanizzm.ultistats.auth.ingress

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import com.github.mihanizzm.ultistats.auth.security.AuthEntryClassifier
import com.github.mihanizzm.ultistats.auth.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class InstallationInfo(
    val contractVersion: String,
    val installationId: UUID,
    val entryMode: AuthEntryMode,
    val publicOrigin: String,
    val secureOrigin: String,
)

@RestController
class InstallationController(
    private val authService: AuthService,
    private val classifier: AuthEntryClassifier,
    private val origins: AuthOriginConfiguration,
) {
    @GetMapping("/api/v1/installation")
    fun installation(request: HttpServletRequest): ResponseEntity<InstallationInfo> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(InstallationInfo("1.0.0", authService.installationId(), classifier.classify(request),
            origins.publicOrigin(), origins.secureOrigin()))
}
