package com.patbaumgartner.stringtelephone

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

fun bearerToken(header: String?): String? =
    header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

class ApiFailure(val status: HttpStatus, message: String) : RuntimeException(message)

@RestController
class ApiController(private val users: Users, private val messages: Messages) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    private fun principal(request: HttpServletRequest): UserRecord =
        bearerToken(request.getHeader("Authorization"))?.let(users::findByToken)
            ?: throw ApiFailure(HttpStatus.UNAUTHORIZED, "unauthorized")

    @GetMapping("/v1/me")
    fun me(request: HttpServletRequest): MeDto = principal(request).let { MeDto(it.username, it.displayName) }

    @GetMapping("/v1/contacts")
    fun contacts(request: HttpServletRequest): ContactsDto {
        val me = principal(request)
        return ContactsDto(users.list().filter { it.active && it.username != me.username }
            .map { ContactDto(it.username, it.displayName, "unknown", null) })
    }

    @GetMapping("/v1/conversations/{peer}/messages")
    fun page(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): MessagesPageDto {
        val me = principal(request)
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val before = cursor?.let {
            decodeMessageCursor(it)?.first ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")
        }
        val rows = messages.page(me.username, peer, before, limit)
        return MessagesPageDto(rows.map(::messageDto), rows.lastOrNull()?.let { encodeMessageCursor(it.createdAt, it.id) })
    }

    @PostMapping("/v1/conversations/{peer}/messages")
    fun send(request: HttpServletRequest, @PathVariable peer: String, @RequestBody body: SendMessageRequest): ResponseEntity<MessageDto> {
        val me = principal(request)
        if (peer == me.username) throw ApiFailure(HttpStatus.BAD_REQUEST, "cannot message yourself")
        if (users.find(peer)?.active != true) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        if (body.recipient != peer) throw ApiFailure(HttpStatus.BAD_REQUEST, "recipient mismatch")
        if (body.content.isEmpty() || body.content.toByteArray(Charsets.UTF_8).size > Messages.MAX_CONTENT_BYTES) {
            throw ApiFailure(HttpStatus.CONTENT_TOO_LARGE, "content size invalid")
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(messageDto(messages.insert(me.username, peer, body.content, body.clientKey)))
    }
}

private fun messageDto(record: MessageRecord) =
    MessageDto(record.id, record.sender, record.recipient, record.content, record.createdAt, record.clientKey)

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<ErrorDto> = ResponseEntity.badRequest().body(ErrorDto(error.message.orEmpty()))

    @ExceptionHandler(PbxUnavailable::class)
    fun pbx(error: PbxUnavailable): ResponseEntity<ErrorDto> = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorDto(error.message.orEmpty()))

    @ExceptionHandler(ApiFailure::class)
    fun failure(error: ApiFailure): ResponseEntity<ErrorDto> = ResponseEntity.status(error.status).body(ErrorDto(error.message.orEmpty()))

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun malformed(): ResponseEntity<ErrorDto> = ResponseEntity.badRequest().body(ErrorDto("invalid request"))
}
