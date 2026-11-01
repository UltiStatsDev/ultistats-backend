package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

class DefaultAuthRequestAccess(private val service:AuthService,private val classifier:AuthEntryClassifier):AuthRequestAccess {
    override fun requirePermission(request:HttpServletRequest,permission:AuthPermission):AuthPrincipal {
        if(classifier.classify(request)==AuthEntryMode.ANONYMOUS) throw AuthProblem(403,"PUBLIC_TRANSPORT_READ_ONLY")
        val principal=resolve(request)
        if(unsafe(request) || hasExpected(request)) validateExpectedIdentity(request,principal)
        if(principal.passwordChangeRequired) throw AuthProblem(403,"AUTH_PASSWORD_CHANGE_REQUIRED")
        if(permission !in principal.permissions) throw AuthProblem(403,"AUTH_PERMISSION_DENIED")
        return principal
    }
    fun resolve(request:HttpServletRequest,logout:Boolean=false):AuthPrincipal {
        val credential=credential(request) ?: throw AuthProblem(401,"AUTH_REQUIRED")
        val principal=if(logout) service.logoutPrincipal(credential) else service.principal(credential)
        request.setAttribute(AuthPrincipal::class.java.name,principal)
        return principal
    }
    fun credential(request:HttpServletRequest):String? {
        val name=if(classifier.classify(request)==AuthEntryMode.SECURE) "__Host-ULTISTATS_SESSION" else "ULTISTATS_LOOPBACK_SESSION"
        val matches=request.cookies.orEmpty().filter { it.name==name }
        if(matches.size>1) throw AuthProblem(401,"AUTH_SESSION_INVALID")
        return matches.singleOrNull()?.value
    }
    fun hasExpected(request:HttpServletRequest)=headers.any { request.getHeaders(it).hasMoreElements() }
    fun validateInstallation(request:HttpServletRequest) {
        val value=header(request,headers[0]) ?: throw AuthProblem(403,"AUTH_CONTEXT_REQUIRED")
        if(value!=service.installationId()) throw AuthProblem(403,"AUTH_IDENTITY_MISMATCH")
    }
    override fun validateExpectedIdentity(request:HttpServletRequest,principal:AuthPrincipal) {
        val values=headers.map { header(request,it) }
        if(values.any { it==null }) throw AuthProblem(403,"AUTH_CONTEXT_REQUIRED")
        if(values!=listOf(principal.installationId,principal.accountId,principal.sessionId)) throw AuthProblem(403,"AUTH_IDENTITY_MISMATCH")
    }
    private fun header(request:HttpServletRequest,name:String):UUID? {
        val values=request.getHeaders(name).toList()
        if(values.isEmpty()) return null
        if(values.size!=1) throw AuthProblem(403,"AUTH_IDENTITY_MISMATCH")
        val value=values[0]
        val parsed=runCatching { UUID.fromString(value) }.getOrNull()
        if(parsed==null || parsed.toString()!=value) throw AuthProblem(403,"AUTH_IDENTITY_MISMATCH")
        return parsed
    }
    companion object {
        val headers=listOf("X-UltiStats-Installation-Id","X-UltiStats-Account-Id","X-UltiStats-Session-Id")
        fun unsafe(request:HttpServletRequest)=request.method !in setOf("GET","HEAD","OPTIONS","TRACE")
    }
}
