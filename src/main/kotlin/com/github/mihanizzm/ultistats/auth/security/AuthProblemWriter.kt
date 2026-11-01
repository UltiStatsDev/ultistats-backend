package com.github.mihanizzm.ultistats.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ProblemDetail
import org.springframework.http.MediaType
import java.net.URI

class AuthProblemWriter(private val mapper:ObjectMapper,private val navigation:AuthHtmlEntryNavigation?=null) {
    fun write(request:HttpServletRequest,response:HttpServletResponse,problem:AuthProblem,beforeExecution:Boolean=true) {
        response.setHeader("Cache-Control","no-store")
        if(canNavigate(request,problem) && navigation?.redirect(request,response,problem)==true) return
        response.status=problem.status
        response.contentType="application/problem+json"
        response.setHeader("Cache-Control","no-store")
        problem.retryAfterSeconds?.let { response.setHeader("Retry-After",it.toString()) }
        val detail=ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(problem.status),"The request could not be authorized.").apply {
            instance=URI.create(request.requestURI)
            setProperty("code",problem.code)
            if(beforeExecution) setProperty("requestRejectedBeforeExecution",true)
        }
        val body=linkedMapOf<String,Any?>("type" to detail.type.toString(),"title" to detail.title,
            "status" to detail.status,"detail" to detail.detail,"instance" to detail.instance.toString(),"code" to problem.code)
        if(beforeExecution) body["requestRejectedBeforeExecution"]=true
        mapper.writeValue(response.outputStream,body)
    }
    private fun canNavigate(request:HttpServletRequest,problem:AuthProblem):Boolean {
        val path=request.requestURI.removePrefix(request.contextPath)
        val authEntry=problem.status==401 && problem.code in setOf("AUTH_REQUIRED","AUTH_SESSION_INVALID","AUTH_SESSION_EXPIRED","AUTH_SESSION_REVOKED")
        val passwordEntry=problem.status==403 && problem.code=="AUTH_PASSWORD_CHANGE_REQUIRED"
        if(request.method!="GET" || (!authEntry && !passwordEntry)) return false
        if(request.getHeader("X-Requested-With")?.equals("XMLHttpRequest",true)==true) return false
        val fetchMode=request.getHeader("Sec-Fetch-Mode")
        if(fetchMode!=null && !fetchMode.equals("navigate",true)) return false
        if(path=="/api" || path.startsWith("/api/") || path=="/v3" || path.startsWith("/v3/")) return false
        if(path.substringAfterLast('.').lowercase() in setOf("js","mjs","css","svg","png","jpg","jpeg","gif","webp","ico","woff","woff2","map")) return false
        if(DefaultAuthRequestAccess.headers.any { request.getHeaders(it).hasMoreElements() }) return false
        return runCatching { MediaType.parseMediaTypes(request.getHeader("Accept") ?: "").any {
            it.type.equals("text",true) && it.subtype.equals("html",true) && it.qualityValue>0
        } }.getOrDefault(false)
    }
}
