package com.github.mihanizzm.ultistats.auth.controller

import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.security.*
import com.github.mihanizzm.ultistats.auth.service.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseCookie
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Duration
import java.util.UUID
import com.github.mihanizzm.ultistats.auth.ingress.AuthClientSource
import org.springframework.beans.factory.ObjectProvider

// Secret-bearing request types deliberately have no generated toString.
class LoginRequest(val login:String,val password:String)
class PasswordRequest(val currentPassword:String,val newPassword:String)
class ReauthRequest(val password:String)
data class ExpectedSessionRequest(val expectedSessionId:UUID)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val service:AuthService,private val access:DefaultAuthRequestAccess,
    private val csrf:AuthCsrfRepository,private val runtime:AuthSecurityRuntime,
    private val limiter:AuthRateLimiter,private val clock:Clock,private val sources:ObjectProvider<AuthClientSource>) {
    @GetMapping("/csrf") fun csrf(request:HttpServletRequest):Map<String,String> {
        val token=request.getAttribute(CsrfToken::class.java.name) as CsrfToken
        return mapOf("headerName" to token.headerName,"token" to token.token)
    }
    @GetMapping("/session") fun session(request:HttpServletRequest)=service.context(access.credential(request))
    @PostMapping("/login") fun login(@RequestBody body:LoginRequest,request:HttpServletRequest,response:HttpServletResponse):AuthContext {
        val source=sources.ifAvailable?.source(request) ?: if(runtime.classifier.classify(request)==AuthEntryMode.LOOPBACK) request.remoteAddr
            else throw AuthProblem(403,"AUTH_PERMISSION_DENIED")
        limiter.check("login-source:$source",20,Duration.ofMinutes(1))
        val login=AuthValidation.login(body.login)
        limiter.check("login-pair:$source:$login",5,Duration.ofMinutes(1))
        val result=service.login(login,body.password,access.credential(request))
        sessionCookie(request,response,result.credential,result.context)
        csrf.saveToken(null,request,response)
        return result.context
    }
    @PostMapping("/renew") fun renew(@RequestBody body:ExpectedSessionRequest,request:HttpServletRequest,response:HttpServletResponse):AuthContext {
        val principal=principal(request)
        expected(body,principal)
        limiter.check("renew:${principal.sessionId}",60,Duration.ofMinutes(1))
        val context=service.renew(principal)
        sessionCookie(request,response,access.credential(request)!!,context)
        return context
    }
    @PostMapping("/logout") fun logout(@RequestBody body:ExpectedSessionRequest,request:HttpServletRequest,response:HttpServletResponse):LogoutResult {
        expected(body,principal(request))
        val result=service.logout(access.credential(request)!!,body.expectedSessionId)
        // Do not clear the shared session cookie: a late response must not remove a newer login.
        csrf.saveToken(null,request,response)
        return result
    }
    @PostMapping("/password") fun password(@RequestBody body:PasswordRequest,request:HttpServletRequest,response:HttpServletResponse):AuthContext {
        val result=service.changePassword(principal(request),body.currentPassword,body.newPassword)
        sessionCookie(request,response,result.credential,result.context)
        csrf.saveToken(null,request,response)
        return result.context
    }
    @PostMapping("/reauth") fun reauth(@RequestBody body:ReauthRequest,request:HttpServletRequest):Map<String,java.time.Instant> {
        val principal=principal(request)
        limiter.check("reauth:${principal.sessionId}",5,Duration.ofMinutes(5))
        return mapOf("reauthenticatedUntil" to service.reauthenticate(principal,body.password))
    }
    private fun principal(request:HttpServletRequest)=request.getAttribute(AuthPrincipal::class.java.name) as AuthPrincipal
    private fun expected(body:ExpectedSessionRequest,principal:AuthPrincipal) {
        if(body.expectedSessionId!=principal.sessionId) throw AuthProblem(403,"AUTH_IDENTITY_MISMATCH")
    }
    private fun sessionCookie(request:HttpServletRequest,response:HttpServletResponse,credential:String,context:AuthContext) {
        val secure=runtime.classifier.classify(request)==AuthEntryMode.SECURE
        val name=if(secure) "__Host-ULTISTATS_SESSION" else "ULTISTATS_LOOPBACK_SESSION"
        val cookie=ResponseCookie.from(name,credential).httpOnly(true).secure(secure).sameSite("Lax").path("/")
            .maxAge(Duration.between(clock.instant(),context.idleExpiresAt).coerceAtLeast(Duration.ZERO)).build()
        response.addHeader("Set-Cookie",cookie.toString())
    }
}
