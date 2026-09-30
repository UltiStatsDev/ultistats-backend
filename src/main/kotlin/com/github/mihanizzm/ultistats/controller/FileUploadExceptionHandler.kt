package com.github.mihanizzm.ultistats.controller

import com.github.mihanizzm.ultistats.service.InvalidFileUploadException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.net.URI

@RestControllerAdvice
class FileUploadExceptionHandler {
    @ExceptionHandler(InvalidFileUploadException::class)
    fun handleInvalidFile(
        exception: InvalidFileUploadException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = badRequest(
        exception.message ?: "File is invalid",
        request,
    )

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleOversizedFile(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        badRequest("File exceeds maximum size of 10 MiB", request)

    private fun badRequest(
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            detail,
        ).apply {
            title = "Invalid file upload"
            instance = URI.create(request.requestURI)
            setProperty("code", "INVALID_FILE_UPLOAD")
        }
        return ResponseEntity.badRequest().body(problem)
    }
}
