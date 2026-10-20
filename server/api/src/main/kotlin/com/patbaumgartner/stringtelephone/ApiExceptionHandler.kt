package com.patbaumgartner.stringtelephone

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * One place where domain failures become HTTP. Responses use RFC 7807
 * `application/problem+json`; internal detail (exception text, class names) is
 * never echoed, matching `spring.web.error.include-message=never`.
 */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(ApiFailure::class)
    fun apiFailure(error: ApiFailure): ResponseEntity<ProblemDetail> =
        problem(error.status, error.message ?: "Request failed")

    @ExceptionHandler(PbxUnavailable::class)
    fun pbxUnavailable(error: PbxUnavailable): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.SERVICE_UNAVAILABLE, error.message ?: "PBX unavailable")

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.BAD_REQUEST, error.message ?: "Invalid request")

    @ExceptionHandler(RateLimited::class)
    fun rateLimited(error: RateLimited): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.TOO_MANY_REQUESTS, error.message ?: "Too many attempts")

    /** Unhandled errors: log with a trace, answer without one. */
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled request failure", error)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
    }

    private fun problem(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail)
        body.setTitle(status.reasonPhrase)
        body.setProperty("code", status.value())
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/problem+json").body(body)
    }
}

/** Thrown when an unauthenticated caller exceeds the token-attempt budget. */
class RateLimited(message: String) : RuntimeException(message)
