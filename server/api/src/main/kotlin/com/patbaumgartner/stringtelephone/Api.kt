package com.patbaumgartner.stringtelephone

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

fun bearerToken(header: String?): String? =
    header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

class ApiFailure(val status: HttpStatus, message: String) : RuntimeException(message)

@RestController
class ApiController(
    private val users: Users,
    private val messages: Messages,
    private val presence: Presence,
    private val healthProbe: HealthProbe,
    private val rateLimiter: RateLimiter,
    private val hub: WsHub,
    private val media: MediaService,
) {
    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    /**
     * Liveness plus database reachability, the same probe as `/actuator/health`
     * below. Returns 503 when PostgreSQL is unreachable, so uptime checks and
     * rolling deployments never route to an instance whose DataSource is dead.
     */
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, String>> =
        if (healthProbe.isReachable()) {
            ResponseEntity.ok(mapOf("status" to "ok", "database" to "up"))
        } else {
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("status" to "unavailable", "database" to "down"))
        }

    private fun principal(request: HttpServletRequest, attemptKey: String? = null): UserRecord {
        val token = bearerToken(request.getHeader("Authorization"))
        if (attemptKey != null && !rateLimiter.allow(attemptKey)) {
            throw RateLimited("Too many authentication attempts; retry in a minute")
        }
        return token?.let(users::findByToken)
            ?: throw ApiFailure(HttpStatus.UNAUTHORIZED, "unauthorized")
    }

    @GetMapping("/v1/me")
    fun me(request: HttpServletRequest): MeDto =
        principal(request, attemptKey = "me:" + remoteAddressOf(request.remoteAddr))
            .let { MeDto(it.username, it.displayName) }

    @GetMapping("/v1/contacts")
    fun contacts(request: HttpServletRequest): ContactsDto {
        val me = principal(request)
        val contacts = users.list().filter { it.active && it.username != me.username }
        val states = presence.all(contacts.map { it.username })
        return ContactsDto(contacts.map {
            val state = states.getValue(it.username)
            ContactDto(it.username, it.displayName, state.presence, state.observedAtEpochMs)
        })
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
            decodeMessageCursor(it) ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "invalid cursor")
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
        val type = body.type.uppercase()
        if (type == "IMAGE") {
            val mediaId = body.mediaId?.takeIf { it.isNotBlank() }
                ?: throw ApiFailure(HttpStatus.BAD_REQUEST, "mediaId required")
            val mediaRecord = media.getRecord(mediaId)
                ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such media")
            if (mediaRecord.owner != me.username || mediaRecord.peer != peer) {
                throw ApiFailure(HttpStatus.FORBIDDEN, "forbidden")
            }
            if (mediaRecord.state != "ready") {
                throw ApiFailure(HttpStatus.BAD_REQUEST, "media not ready")
            }
            val stored = messages.insertMedia(me.username, peer, body.clientKey, mediaId, mediaRecord)
            return ResponseEntity.status(HttpStatus.CREATED).body(messageDto(stored))
        }
        if (body.content.isEmpty() || body.content.toByteArray(Charsets.UTF_8).size > Messages.MAX_CONTENT_BYTES) {
            throw ApiFailure(HttpStatus.CONTENT_TOO_LARGE, "content size invalid")
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(messageDto(messages.insert(me.username, peer, body.content, body.clientKey)))
    }

    /**
     * Marks the peer's messages as read by the caller (ADR-0010). Only the
     * recipient may mark a message read; the server derives both sides from
     * the authenticated principal, never from the request body.
     *
     * The client calls this only from the foreground chat screen while the
     * messages are rendered — never from a notification handler, a
     * background sync or a cursor catch-up.
     */
    @PostMapping("/v1/conversations/{peer}/read")
    fun markRead(
        request: HttpServletRequest,
        @PathVariable peer: String,
        @RequestBody(required = false) body: MarkReadRequest?,
    ): MarkReadResponse {
        val me = principal(request)
        if (peer == me.username) throw ApiFailure(HttpStatus.BAD_REQUEST, "cannot read your own messages")
        if (users.find(peer) == null) throw ApiFailure(HttpStatus.NOT_FOUND, "no such user")
        val upTo = body?.upToMessageId?.takeIf { it.isNotBlank() }?.let { id ->
            val record = messages.findById(id) ?: throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
            if (record.sender != peer || record.recipient != me.username) {
                throw ApiFailure(HttpStatus.NOT_FOUND, "no such message")
            }
            record.createdAt to record.id
        }
        val mark = messages.markRead(me.username, peer, upTo)
        if (mark.messageIds.isNotEmpty()) hub.broadcastMessageRead(peer, mark)
        return MarkReadResponse(mark.messageIds, mark.readAt)
    }
}

private fun messageDto(record: MessageRecord) =
    MessageDto(record.id, record.sender, record.recipient, record.content, record.createdAt, record.clientKey, record.readAt, record.type, record.mediaId, record.width, record.height)
