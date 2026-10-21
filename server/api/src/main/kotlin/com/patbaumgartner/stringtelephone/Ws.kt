package com.patbaumgartner.stringtelephone

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler

/**
 * Realtime gateway: authenticates the WebSocket handshake, keeps the live
 * sessions in [WsSessions] and pushes the events the app subscribes to.
 *
 * The hub owns the session lifecycle; the frames themselves are built by
 * [WsFrames] and the presence rules by [Presence]. The dependency direction
 * is one-way (hub to presence, both to the session registry), so the Spring
 * context never has a cycle to resolve.
 */
class WsHub(
    private val users: Users,
    private val presence: Presence,
    private val sessions: WsSessions,
) : TextWebSocketHandler() {

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val user = bearerToken(session.handshakeHeaders.getFirst("Authorization"))?.let(users::findByToken)
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.attributes["username"] = user.username
        if (sessions.open(user.username, session)) presence.publish(user.username)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val username = session.attributes["username"] as? String ?: return
        if (sessions.close(username, session)) presence.publish(username)
    }

    fun isConnected(username: String): Boolean = sessions.isConnected(username)

    fun hasSessions(): Boolean = sessions.hasSessions()

    /** Drops every session of a user; used when an account is disabled or reset. */
    fun disconnect(username: String) = sessions.disconnect(username)

    fun broadcastNewMessage(message: MessageRecord) =
        sessions.sendTo(setOf(message.sender, message.recipient), WsFrames.newMessage(message))

    /**
     * Tells the author which messages the reader has read (ADR-0010). The
     * author turns them into double ticks; the reader learns the receipt from
     * the response to its own mark.
     */
    fun broadcastMessageRead(peer: String, mark: ReadMark) =
        sessions.send(peer, WsFrames.messageRead(mark))
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfiguration(private val hub: WsHub) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/v1/ws")
    }
}
