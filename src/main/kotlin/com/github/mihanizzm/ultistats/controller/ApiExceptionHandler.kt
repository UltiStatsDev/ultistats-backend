package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.dto.common.InvalidSortException
import com.github.mihanizzm.ultistats.validation.query.InvalidQueryParameterException
import com.github.mihanizzm.ultistats.validation.match.MatchProblem
import com.github.mihanizzm.ultistats.validation.match.MatchProblemCode
import com.github.mihanizzm.ultistats.service.EventIdempotencyConflictException
import com.github.mihanizzm.ultistats.service.PointLineupProblemCode
import com.github.mihanizzm.ultistats.service.PointLineupRejectedException
import com.github.mihanizzm.ultistats.exception.AnnouncementConflictException
import com.github.mihanizzm.ultistats.exception.InvalidPatchException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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

    @ExceptionHandler(AnnouncementConflictException::class)
    fun handleAnnouncementConflict(
        exception: AnnouncementConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.detail).apply {
            title = "Announcement conflict"
            instance = URI.create(request.requestURI)
            setProperty("code", exception.code.name)
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
    }

    @ExceptionHandler(PointLineupRejectedException::class)
    fun rejected(error: PointLineupRejectedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val invalid = error.code == PointLineupProblemCode.INVALID_REQUEST
        val status = if (invalid) HttpStatus.BAD_REQUEST else HttpStatus.CONFLICT
        val problem = ProblemDetail.forStatusAndDetail(status, error.message.orEmpty()).apply {
            title = if (invalid) "Invalid point lineup" else "Point lineup conflict"
            instance = URI.create(request.requestURI)
            setProperty("code", error.code.name)
            error.batch?.let { batch ->
                setProperty("lastSequenceNumber", batch.lastSequenceNumber)
                setProperty("logRevision", batch.logRevision)
                batch.eventId?.let { setProperty("eventId", it) }
                batch.eventIndex?.let { setProperty("eventIndex", it) }
            }
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).apply {
            if (error.code == PointLineupProblemCode.POINT_LINEUP_CLIENT_REQUIRED) varyBy(POINT_LINEUPS_HEADER)
        }.body(problem)
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
