package com.patbaumgartner.stringtelephone

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey

val ADMIN_TOKEN_KEY = AttributeKey<String>("AdminTokenHash")

/**
 * Authenticated user API. The authenticated principal (bearer token) is
 * authoritative; user ids in bodies are ignored. A revoked or unknown token
 * fails closed with 401.
 */
fun Route.apiRoutes(users: Users, messages: Messages) {
    route("/v1") {
        get("/me") {
            val user = requireUser(call, users) ?: return@get
            call.respond(MeDto(user.username, user.displayName))
        }

        get("/contacts") {
            val me = requireUser(call, users) ?: return@get
            val others = users.list().filter { it.username != me.username && it.active }
            call.respond(
                ContactsDto(
                    others.map {
                        ContactDto(
                            username = it.username,
                            displayName = it.displayName,
                            presence = "unknown",
                            presenceUpdatedAtEpochMs = null,
                        )
                    },
                ),
            )
        }

        get("/conversations/{peer}/messages") {
            val me = requireUser(call, users) ?: return@get
            val peer = call.parameters["peer"].orEmpty()
            if (peer.isEmpty() || users.find(peer) == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("no such user"))
                return@get
            }
            val cursor = call.request.queryParameters["cursor"]
            var before: Long? = null
            if (cursor != null) {
                val decoded = decodeMessageCursor(cursor)
                if (decoded == null) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("invalid cursor"))
                    return@get
                }
                before = decoded.first
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val rows = messages.page(me.username, peer, before, limit)
            val next = rows.lastOrNull()?.let { encodeMessageCursor(it.createdAt, it.id) }
            call.respond(
                MessagesPageDto(
                    rows.map { MessageDto(it.id, it.sender, it.recipient, it.content, it.createdAt, it.clientKey) },
                    next,
                ),
            )
        }

        post("/conversations/{peer}/messages") {
            val me = requireUser(call, users) ?: return@post
            val peer = call.parameters["peer"].orEmpty()
            if (peer == me.username) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("cannot message yourself"))
                return@post
            }
            val peerRecord = users.find(peer)
            if (peerRecord == null || !peerRecord.active) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("no such user"))
                return@post
            }
            val req = call.receive<SendMessageRequest>()
            if (req.recipient != peer) {
                call.respond(HttpStatusCode.BadRequest, ErrorDto("recipient mismatch"))
                return@post
            }
            val bytes = req.content.toByteArray(Charsets.UTF_8)
            if (req.content.isEmpty() || bytes.size > Messages.MAX_CONTENT_BYTES) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorDto("content size invalid"))
                return@post
            }
            val saved = messages.insert(me.username, peer, req.content, req.clientKey)
            call.respond(HttpStatusCode.Created, MessageDto(saved.id, saved.sender, saved.recipient, saved.content, saved.createdAt, saved.clientKey))
        }
    }
}

private suspend fun requireUser(
    call: ApplicationCall,
    users: Users,
): UserRecord? {
    val token = bearerToken(call)
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
        return null
    }
    val user = users.findByToken(token)
    if (user == null) {
        call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
        return null
    }
    return user
}
