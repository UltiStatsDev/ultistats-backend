package com.github.mihanizzm.ultistats.auth.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ProblemDetail
import java.net.URI

class AuthProblemWriter(private val mapper:ObjectMapper) {
    fun write(request:HttpServletRequest,response:HttpServletResponse,problem:AuthProblem,beforeExecution:Boolean=true) {
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
}
