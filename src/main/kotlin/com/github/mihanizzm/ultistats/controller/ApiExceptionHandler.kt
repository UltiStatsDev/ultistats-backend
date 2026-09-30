package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = MatchProblem(
            code = MatchProblemCode.INVALID_REQUEST,
            title = "Invalid request body",
            detail = "Request body is malformed or contains unsupported values",
        )
        return ResponseEntity.badRequest()
            .body(problem.toProblemDetail(HttpStatus.BAD_REQUEST, URI.create(request.requestURI)))
    }
}
