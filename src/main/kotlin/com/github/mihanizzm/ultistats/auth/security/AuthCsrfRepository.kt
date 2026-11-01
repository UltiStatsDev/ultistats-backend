package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthEntryMode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository

class AuthCsrfRepository(private val classifier:AuthEntryClassifier):CsrfTokenRepository {
    private val secure=repository("__Host-ULTISTATS_CSRF",true)
    private val loopback=repository("ULTISTATS_LOOPBACK_CSRF",false)
    private fun repository(name:String,secure:Boolean)=CookieCsrfTokenRepository().apply {
        setHeaderName("X-CSRF-TOKEN")
        setCookieName(name)
        setCookiePath("/")
        setCookieCustomizer { it.httpOnly(true).secure(secure).sameSite("Lax").path("/") }
    }
    private fun repository(request:HttpServletRequest)=if(classifier.classify(request)==AuthEntryMode.SECURE) secure else loopback
    override fun generateToken(request:HttpServletRequest):CsrfToken=repository(request).generateToken(request)
    override fun saveToken(token:CsrfToken?,request:HttpServletRequest,response:HttpServletResponse)=repository(request).saveToken(token,request,response)
    override fun loadToken(request:HttpServletRequest):CsrfToken?=repository(request).loadToken(request)
}
