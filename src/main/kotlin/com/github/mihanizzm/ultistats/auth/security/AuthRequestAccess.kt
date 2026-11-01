package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.auth.model.AuthPrincipal
import jakarta.servlet.http.HttpServletRequest

interface AuthRequestAccess {
    fun requirePermission(request: HttpServletRequest, permission: AuthPermission): AuthPrincipal
    fun validateExpectedIdentity(request: HttpServletRequest, principal: AuthPrincipal)
}
