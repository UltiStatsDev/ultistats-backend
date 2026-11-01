package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import jakarta.servlet.http.HttpServletRequest

sealed interface AuthRequirement {
    data object PublicRead : AuthRequirement
    data object Core : AuthRequirement
    data object Deny : AuthRequirement
    data class Permission(val permission: AuthPermission) : AuthRequirement
}
interface AuthSurfacePolicy { fun requirement(request: HttpServletRequest): AuthRequirement }
