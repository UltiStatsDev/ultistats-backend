package com.github.mihanizzm.ultistats.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.mihanizzm.ultistats.auth.ingress.AuthOriginConfiguration
import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.*
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.CorsFilter
import org.springframework.web.cors.CorsUtils
import jakarta.servlet.http.HttpServletRequestWrapper
import java.net.URI
import java.time.Clock
import com.github.mihanizzm.ultistats.auth.repository.AuthTransactionFence

class AuthSecurityRuntime(val classifier:AuthEntryClassifier,val origins:AuthOriginConfiguration?)

@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class AuthSecurityConfig {
    @Bean fun authRuntime(classifier:ObjectProvider<AuthEntryClassifier>,origins:ObjectProvider<AuthOriginConfiguration>)=
        AuthSecurityRuntime(classifier.getIfAvailable { object:AuthEntryClassifier {
            override fun classify(request:HttpServletRequest)=AuthEntryMode.ANONYMOUS
        } },origins.ifAvailable)
    @Bean fun authAccess(service:AuthService,runtime:AuthSecurityRuntime)=DefaultAuthRequestAccess(service,runtime.classifier)
    @Bean fun authCsrf(runtime:AuthSecurityRuntime)=AuthCsrfRepository(runtime.classifier)
    @Bean fun authLimiter(clock:Clock)=AuthRateLimiter(clock)
    @Bean fun authProblems(mapper:ObjectMapper,navigation:ObjectProvider<AuthHtmlEntryNavigation>)=AuthProblemWriter(mapper,navigation.ifAvailable)
    @Bean fun authDomainAdvisor(fence:AuthTransactionFence,access:DefaultAuthRequestAccess)=AuthDomainTransactionAdvisor(fence,access)
    // Disables Boot's generated default-user password without an HTTP login provider.
    @Bean fun authenticationManager()=AuthenticationManager { throw BadCredentialsException("Cookie authentication required") }

    @Bean fun authChain(http:HttpSecurity,runtime:AuthSecurityRuntime,access:DefaultAuthRequestAccess,csrf:AuthCsrfRepository,
        policies:ObjectProvider<AuthSurfacePolicy>,writer:AuthProblemWriter):SecurityFilterChain {
        val boundary=AuthBoundaryFilter(runtime,access,policies,writer)
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .securityContext { it.requireExplicitSave(true) }
            .requestCache { it.disable() }
            .formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .cors { }
            .csrf { it.csrfTokenRepository(csrf) }
            .exceptionHandling {
                it.authenticationEntryPoint { request,response,_ -> writer.write(request,response,AuthProblem(401,"AUTH_REQUIRED")) }
                it.accessDeniedHandler { request,response,denied ->
                    val code=if(denied is org.springframework.security.web.csrf.CsrfException) "CSRF_INVALID" else "AUTH_PERMISSION_DENIED"
                    writer.write(request,response,AuthProblem(403,code))
                }
            }
            .headers { it.frameOptions { frame -> frame.sameOrigin() } }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterBefore(boundary,CorsFilter::class.java)
        return http.build()
    }
}

private class AuthBoundaryFilter(private val runtime:AuthSecurityRuntime,private val access:DefaultAuthRequestAccess,
    private val policies:ObjectProvider<AuthSurfacePolicy>,private val writer:AuthProblemWriter):OncePerRequestFilter() {
    override fun doFilterInternal(request:HttpServletRequest,response:HttpServletResponse,chain:FilterChain) {
        try {
            if(CorsUtils.isPreFlightRequest(request)) {
                val intended=object:HttpServletRequestWrapper(request) {
                    override fun getMethod():String=request.getHeader("Access-Control-Request-Method")
                }
                val requirement=requirement(intended)
                if(runtime.classifier.classify(request)==AuthEntryMode.ANONYMOUS && requirement!=AuthRequirement.PublicRead)
                    throw AuthProblem(403,"PUBLIC_TRANSPORT_READ_ONLY")
                if(requirement==AuthRequirement.Deny) throw AuthProblem(403,"AUTH_PERMISSION_DENIED")
                checkOrigin(request)
                // Credential-free preflight is completed by configured CorsFilter, never a controller.
                chain.doFilter(request,response)
                return
            }
            val requirement=requirement(request)
            request.setAttribute(AuthRequirement::class.java.name,requirement)
            if(requirement==AuthRequirement.PublicRead) { chain.doFilter(request,response);return }
            response.setHeader("Cache-Control","no-store")
            if(runtime.classifier.classify(request)==AuthEntryMode.ANONYMOUS) throw AuthProblem(403,"PUBLIC_TRANSPORT_READ_ONLY")
            if(requirement==AuthRequirement.Deny) throw AuthProblem(403,"AUTH_PERMISSION_DENIED")
            checkOrigin(request)
            when(requirement) {
                is AuthRequirement.Permission -> access.requirePermission(request,requirement.permission)
                AuthRequirement.Core -> core(request)
                else -> Unit
            }
            chain.doFilter(request,response)
        } catch(problem:AuthProblem) { writer.write(request,response,problem) }
    }
    private fun core(request:HttpServletRequest) {
        val path=request.requestURI.removePrefix(request.contextPath)
        when(path) {
            "/api/v1/auth/login" -> access.validateInstallation(request)
            "/api/v1/auth/csrf" -> if(access.hasExpected(request)) access.validateExpectedIdentity(request,access.resolve(request))
            "/api/v1/auth/session" -> {
                if(access.credential(request)!=null || access.hasExpected(request)) {
                    val principal=access.resolve(request)
                    if(access.hasExpected(request)) access.validateExpectedIdentity(request,principal)
                }
            }
            else -> {
                val principal=access.resolve(request,logout=path=="/api/v1/auth/logout")
                access.validateExpectedIdentity(request,principal)
                if(principal.passwordChangeRequired && path!="/api/v1/auth/password" && path!="/api/v1/auth/logout")
                    throw AuthProblem(403,"AUTH_PASSWORD_CHANGE_REQUIRED")
            }
        }
    }
    private fun requirement(request:HttpServletRequest):AuthRequirement {
        val path=request.requestURI.removePrefix(request.contextPath)
        val method=request.method
        if(method in setOf("GET","HEAD") && path in setOf("/api/v1/installation","/health/ready")) return AuthRequirement.PublicRead
        if(method=="GET" && path in setOf("/api/v1/auth/session","/api/v1/auth/csrf")) return AuthRequirement.Core
        if(method=="POST" && path in setOf("/api/v1/auth/login","/api/v1/auth/logout","/api/v1/auth/renew","/api/v1/auth/password","/api/v1/auth/reauth")) return AuthRequirement.Core
        if((path=="/api/v1/accounts" && method in setOf("GET","POST")) ||
            (path.matches(Regex("/api/v1/accounts/[^/]+")) && method=="PATCH") ||
            (path.matches(Regex("/api/v1/accounts/[^/]+/password-reset")) && method=="POST"))
            return AuthRequirement.Permission(AuthPermission.ACCOUNT_ADMIN)
        return policies.ifAvailable?.requirement(request) ?: AuthRequirement.Deny
    }
    private fun checkOrigin(request:HttpServletRequest) {
        val allowed=runtime.origins?.secureOrigins().orEmpty()
        val origin=request.getHeader("Origin")
        if(origin!=null && origin !in allowed) throw AuthProblem(403,"CSRF_INVALID")
        val referer=request.getHeader("Referer")
        if(referer!=null) {
            val uri=runCatching { URI(referer) }.getOrNull()
            val refOrigin=uri?.let { "${it.scheme}://${it.rawAuthority}" }
            if(refOrigin !in allowed) throw AuthProblem(403,"CSRF_INVALID")
        }
    }
}
