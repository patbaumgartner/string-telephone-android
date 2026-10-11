package com.patbaumgartner.stringtelephone

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Fan-out hub for authenticated WebSocket sessions (ADR-0003). Each connected
 * user gets an unbounded queue of text frames; the route pumps the queue.
 */
class WsHub(private val json: Json = Json) {

    /** username -> outbound queue. A user has at most one live session. */
    private val queues = java.util.concurrent.ConcurrentHashMap<String, Channel<String>>()

    fun connect(username: String): Channel<String> {
        val channel = Channel<String>(capacity = Channel.UNLIMITED)
        queues[username] = channel
        return channel
    }

    fun disconnect(username: String) {
        queues.remove(username)?.close()
    }

    /** Delivers a new-message event to both parties when connected. */
    fun broadcastNewMessage(message: MessageRecord) {
        val payload = json.encodeToString(
            WsMessageDto(
                type = "new-message",
                payload = json.encodeToString(
                    WsNewMessageEvent.serializer(),
                    WsNewMessageEvent(
                        id = message.id,
                        sender = message.sender,
                        recipient = message.recipient,
                        content = message.content,
                        createdAt = message.createdAt,
                        clientKey = message.clientKey,
                    ),
                ),
            ),
        )
        for (username in setOf(message.sender, message.recipient)) {
            queues[username]?.trySend(payload)
        }
    }
}

/**
 * Authenticated WebSocket stream at /v1/ws?token=<api token>. The token rides
 * in the query string (simplest cross-platform handshake); it must be an
 * active user token or the socket is closed with 1008. Server-pushed frames
 * are JSON [WsMessageDto] envelopes; client frames are read and discarded.
 */
fun Route.wsRoutes(hub: WsHub, users: Users) {
    webSocket("/v1/ws") {
        val user = call.request.queryParameters["token"]?.let { users.findByToken(it) }
        if (user == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val queue = hub.connect(user.username)
        val pump = launch {
            for (payload in queue) {
                send(Frame.Text(payload))
            }
        }
        try {
            for (frame in incoming) {
                // Read incoming frames to detect client disconnects; content is ignored.
                if (frame is Frame.Close) break
            }
        } finally {
            hub.disconnect(user.username)
            queue.close()
            close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        }
    }
}
