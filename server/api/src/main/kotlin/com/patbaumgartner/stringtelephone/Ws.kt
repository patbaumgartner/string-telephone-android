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

class WsHub : TextWebSocketHandler() {
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()
    lateinit var users: Users
    var presence: Presence? = null

    fun isConnected(username: String): Boolean = sessions[username]?.isNotEmpty() == true

    fun hasSessions(): Boolean = sessions.values.any { it.isNotEmpty() }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val user = bearerToken(session.handshakeHeaders.getFirst("Authorization"))?.let(users::findByToken)
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        val firstSession = synchronized(this) {
            if (users.findByToken(bearerToken(session.handshakeHeaders.getFirst("Authorization")).orEmpty()) == null) {
                session.close(CloseStatus.POLICY_VIOLATION)
                return
            }
            session.attributes["username"] = user.username
            val userSessions = sessions.computeIfAbsent(user.username) { ConcurrentHashMap() }
            val wasEmpty = userSessions.isEmpty()
            userSessions[session.id] = ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024)
            wasEmpty
        }
        if (firstSession) presenceChanged(user.username)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val username = session.attributes["username"] as? String ?: return
        val lastSession = synchronized(this) {
            val userSessions = sessions[username] ?: return
            userSessions.remove(session.id) != null && userSessions.isEmpty()
        }
        if (lastSession) presenceChanged(username)
    }

    /** Pushes the subject's current presence to every other connected user. */
    fun presenceChanged(username: String) {
        val presence = presence ?: return
        val snapshot = presence.of(username)
        val payload = Json.encodeToString(WsMessageDto("presence", Json.encodeToString(
            WsPresenceEvent(username, snapshot.presence, snapshot.observedAtEpochMs),
        )))
        for ((recipient, userSessions) in sessions) {
            if (recipient == username) continue
            userSessions.values.forEach { send(recipient, it, payload) }
        }
    }

    @Synchronized
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
            org.slf4j.LoggerFactory.getLogger(WsHub::class.java).warn("WebSocket delivery failed for {}", username, error)
            sessions[username]?.remove(session.id)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfiguration(private val hub: WsHub, users: Users, presence: Presence) : WebSocketConfigurer {
    init {
        hub.users = users
        hub.presence = presence
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/v1/ws")
    }
}
