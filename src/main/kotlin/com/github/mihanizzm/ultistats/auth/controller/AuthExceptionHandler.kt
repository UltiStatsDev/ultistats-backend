package com.github.mihanizzm.ultistats.auth.controller

import com.github.mihanizzm.ultistats.auth.security.AuthProblem
import com.github.mihanizzm.ultistats.auth.security.AuthProblemWriter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(basePackages=["com.github.mihanizzm.ultistats.auth"])
class AuthExceptionHandler(private val writer:AuthProblemWriter) {
    @ExceptionHandler(AuthProblem::class)
    fun problem(problem:AuthProblem,request:HttpServletRequest,response:HttpServletResponse) = writer.write(request,response,problem,false)
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(request:HttpServletRequest):ProblemDetail = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid auth request fields").apply {
        instance=URI.create(request.requestURI);setProperty("code","INVALID_REQUEST")
    }
}
