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

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val user = bearerToken(session.handshakeHeaders.getFirst("Authorization"))?.let(users::findByToken)
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        synchronized(this) {
            if (users.findByToken(bearerToken(session.handshakeHeaders.getFirst("Authorization")).orEmpty()) == null) {
                session.close(CloseStatus.POLICY_VIOLATION)
                return
            }
            session.attributes["username"] = user.username
            sessions.computeIfAbsent(user.username) { ConcurrentHashMap() }[session.id] =
                ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val username = session.attributes["username"] as? String ?: return
        sessions[username]?.remove(session.id)
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
            sessions[username]?.values?.forEach { session ->
                if (session.isOpen) {
                    try {
                        session.sendMessage(TextMessage(payload))
                    } catch (error: java.io.IOException) {
                        org.slf4j.LoggerFactory.getLogger(WsHub::class.java).warn("WebSocket delivery failed for {}", username, error)
                        sessions[username]?.remove(session.id)
                    }
                }
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfiguration(private val hub: WsHub, users: Users) : WebSocketConfigurer {
    init { hub.users = users }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/v1/ws")
    }
}
