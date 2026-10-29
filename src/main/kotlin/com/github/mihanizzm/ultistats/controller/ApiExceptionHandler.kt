package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.common.InvalidSortException
import com.github.mihanizzm.ultistats.validation.query.InvalidQueryParameterException
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import com.github.mihanizzm.ultistats.service.EventIdempotencyConflictException
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(InvalidPatchException::class)
    fun handleInvalidPatch(
        exception: InvalidPatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = MatchProblem(
            code = MatchProblemCode.INVALID_REQUEST,
            title = "Invalid patch request",
            detail = exception.message ?: "Patch request is invalid",
        )
        return ResponseEntity.badRequest()
            .body(problem.toProblemDetail(HttpStatus.BAD_REQUEST, URI.create(request.requestURI)))
    }

    @ExceptionHandler(EventIdempotencyConflictException::class)
    fun handleEventIdempotencyConflict(
        exception: EventIdempotencyConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = MatchProblem(
            code = MatchProblemCode.EVENT_IDEMPOTENCY_CONFLICT,
            title = "Event idempotency conflict",
            detail = "Event ${exception.eventId} already exists with a different payload",
        )
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(problem.toProblemDetail(HttpStatus.CONFLICT, URI.create(request.requestURI)))
    }

    @ExceptionHandler(InvalidQueryParameterException::class)
    fun handleInvalidQuery(exception: InvalidQueryParameterException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        invalidParameters(exception.message ?: "Invalid query parameters", request)

    @ExceptionHandler(InvalidSortException::class)
    fun handleInvalidSort(exception: InvalidSortException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        invalidParameters(exception.message ?: "Invalid sorting parameters", request)

    @ExceptionHandler(BindException::class)
    fun handleBindingFailure(exception: BindException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        invalidParameters(
            exception.fieldErrors.joinToString("; ") { error ->
                if (error.isBindingFailure) {
                    "Invalid '${error.field}': use the documented parameter type and enum values"
                } else {
                    "Invalid '${error.field}': ${error.defaultMessage ?: "constraint violated"}"
                }
            },
            request,
        )

    private fun invalidParameters(detail: String, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val problem = MatchProblem(MatchProblemCode.INVALID_REQUEST, "Invalid query parameters", detail)
        return ResponseEntity.badRequest()
            .body(problem.toProblemDetail(HttpStatus.BAD_REQUEST, URI.create(request.requestURI)))
    }

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
