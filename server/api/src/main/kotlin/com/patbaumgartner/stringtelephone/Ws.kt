package com.patbaumgartner.stringtelephone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry and broadcaster for realtime sessions, plus a thin
 * [TextWebSocketHandler] view of itself: Spring's handler registry needs a
 * handler instance, while the services need the registry. Exposing the
 * subclass keeps both seams without field injection or a nullable back
 * reference.
 */
class WsHub(
    private val users: Users,
    private val presence: Presence,
) {
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()
    private val log = org.slf4j.LoggerFactory.getLogger(WsHub::class.java)
    val handler: TextWebSocketHandler = object : TextWebSocketHandler() {
        override fun afterConnectionEstablished(session: WebSocketSession) = this@WsHub.afterConnectionEstablished(session)
        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) = this@WsHub.afterConnectionClosed(session, status)
    }

    fun isConnected(username: String): Boolean = sessions[username]?.isNotEmpty() == true

    fun hasSessions(): Boolean = sessions.values.any { it.isNotEmpty() }

    private fun afterConnectionEstablished(session: WebSocketSession) {
        val user = bearerToken(session.handshakeHeaders.getFirst("Authorization"))?.let(users::findByToken)
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        val firstSession = synchronized(this) {
            session.attributes["username"] = user.username
            val userSessions = sessions.computeIfAbsent(user.username) { ConcurrentHashMap() }
            val wasEmpty = userSessions.isEmpty()
            userSessions[session.id] = ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024)
            wasEmpty
        }
        if (firstSession) presenceChanged(user.username)
    }

    private fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val username = session.attributes["username"] as? String ?: return
        val lastSession = synchronized(this) {
            val userSessions = sessions[username] ?: return
            userSessions.remove(session.id) != null && userSessions.isEmpty()
        }
        if (lastSession) presenceChanged(username)
    }

    /** Pushes the subject's current presence to every other connected user. */
    fun presenceChanged(username: String) {
        val snapshot = presence.of(username)
        val payload = Json.encodeToString(WsMessageDto("presence", Json.encodeToString(
            WsPresenceEvent(username, snapshot.presence, snapshot.observedAtEpochMs),
        )))
        for ((recipient, userSessions) in sessions) {
            if (recipient == username) continue
            userSessions.values.forEach { send(recipient, it, payload) }
        }
    }

    fun disconnect(username: String) {
        sessions.remove(username)?.values?.forEach { session ->
            if (session.isOpen) session.close(CloseStatus.POLICY_VIOLATION)
        }
    }

    fun broadcastNewMessage(message: MessageRecord) {
        val payload = Json.encodeToString(WsMessageDto("new-message", Json.encodeToString(
            WsNewMessageEvent(message.id, message.sender, message.recipient, message.content, message.createdAt, message.clientKey),
        )))
        for (username in setOf(message.sender, message.recipient)) {
            sessions[username]?.values?.forEach { send(username, it, payload) }
        }
    }

    private fun send(username: String, session: WebSocketSession, payload: String) {
        if (!session.isOpen) return
        try {
            session.sendMessage(TextMessage(payload))
        } catch (error: java.io.IOException) {
            log.warn("WebSocket delivery failed for {}", username, error)
            sessions[username]?.remove(session.id)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfiguration(private val hub: WsHub) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub.handler, "/v1/ws")
    }
}
