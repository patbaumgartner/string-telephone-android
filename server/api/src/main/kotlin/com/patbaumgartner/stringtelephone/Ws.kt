package com.patbaumgartner.stringtelephone

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
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
    private val jdbcClient: JdbcClient,
    private val groups: Groups,
) : TextWebSocketHandler() {

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val user = bearerToken(session.handshakeHeaders.getFirst("Authorization"))?.let(users::findByToken)
        if (user == null) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.attributes["username"] = user.username
        if (sessions.open(user.username, session)) {
            // First live session: the user is reachable again, so the stored
            // last-seen no longer describes them (Phase 13).
            users.clearLastSeen(user.username)
            presence.publish(user.username)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val username = session.attributes["username"] as? String ?: return
        if (sessions.close(username, session)) {
            // Last session gone: stamp when they were last reachable so
            // contacts can show "last seen ..." (Phase 13). Disabled or
            // revoked accounts keep their stamp; nobody may fetch it anyway.
            val user = users.find(username)
            if (user?.active == true) users.markLastSeen(username, System.currentTimeMillis())
            presence.publish(username)
        }
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val username = session.attributes["username"] as? String ?: return
        handleClientFrame(username, message.payload)
    }

    /**
     * Client frames are ephemeral signals only; messages ride the HTTP send
     * endpoint. The accepted frames are the typing signals: the pairwise
     * variant (Phase 7) fans out to the sender's conversation peers, the
     * group variant (Phase 19) to every member except the composer.
     */
    internal fun handleClientFrame(sender: String, frame: String) {
        val envelope = runCatching { Json.decodeFromString<WsMessageDto>(frame) }.getOrNull() ?: return
        if (envelope.type != "typing") return
        // Group variant first: its payload carries a groupId.
        val groupPayload = runCatching {
            Json.decodeFromString<WsClientGroupTypingPayload>(envelope.payload)
        }.getOrNull()
        if (groupPayload != null) {
            val members = groups.memberNames(groupPayload.groupId)
            if (sender !in members) return
            broadcastGroupTyping(groupPayload.groupId, sender, members, groupPayload.typing)
            return
        }
        val typing = runCatching {
            Json.decodeFromString<WsClientTypingPayload>(envelope.payload).typing
        }.getOrNull() ?: return
        val peers = recentPeers(sender)
        if (peers.isEmpty()) return
        sessions.sendTo(peers, WsFrames.typing(sender, typing))
    }

    /**
     * Distinct conversation partners of [user] in both directions, most
     * recently active first. Used by the typing fan-out to reach everyone
     * who shares a conversation with the sender.
     */
    private fun recentPeers(user: String, limit: Int = 50): List<String> =
        jdbcClient.sql(
            "SELECT peer FROM (" +
                "SELECT recipient AS peer, MAX(created_at) FROM messages WHERE sender = ? GROUP BY recipient " +
                "UNION ALL " +
                "SELECT sender AS peer, MAX(created_at) FROM messages WHERE recipient = ? GROUP BY sender" +
                ") recent WHERE peer <> ? GROUP BY peer ORDER BY MAX(recent.latest) DESC LIMIT ?",
        ).params(user, user, user, limit).query(String::class.java).list().filterNotNull()

    fun isConnected(username: String): Boolean = sessions.isConnected(username)

    fun hasSessions(): Boolean = sessions.hasSessions()

    /** Drops every session of a user; used when an account is disabled or reset. */
    fun disconnect(username: String) = sessions.disconnect(username)

    fun broadcastNewMessage(message: MessageRecord) =
        sessions.sendTo(setOf(message.sender, message.recipient), WsFrames.newMessage(message))

    /**
     * Group message fan-out (Phase 19): every *current* member receives the
     * frame. Membership is read live, so members who joined a heartbeat ago
     * get the frame and removed members stop receiving immediately.
     */
    fun broadcastGroupMessage(message: MessageRecord, members: List<String>) =
        sessions.sendTo(members.toSet(), WsFrames.groupNewMessage(message))

    /**
     * Per-member group read fan-out (Phase 19): every member's devices
     * learn which messages [reader] read, so the author's "3 read" counter
     * converges and the reader's other devices apply it idempotently.
     */
    fun broadcastGroupRead(groupId: String, reader: String, members: List<String>, mark: ReadMark) =
        sessions.sendTo(members.toSet(), WsFrames.groupMessageRead(groupId, reader, mark))

    /** Group reaction fan-out (Phase 19): mirrors the pairwise one over membership. */
    fun broadcastGroupReaction(message: MessageRecord, members: List<String>, author: String, emoji: String, added: Boolean) =
        sessions.sendTo(members.toSet(), WsFrames.groupReaction(message, author, emoji, added))

    /** Group deletion fan-out (Phase 19): mirrors the pairwise one over membership. */
    fun broadcastGroupMessageDeleted(message: MessageRecord, members: List<String>) =
        sessions.sendTo(members.toSet(), WsFrames.groupMessageDeleted(message))

    /** Group typing fan-out (Phase 19): everyone except the composer. */
    fun broadcastGroupTyping(groupId: String, from: String, members: List<String>, typing: Boolean) =
        sessions.sendTo((members.toSet() - from), WsFrames.groupTyping(groupId, from, typing))

    /**
     * Fan-out for a reaction change (ADR-0011, Phase 8): both participants
     * receive the frame, so each device can update its chips immediately.
     */
    fun broadcastReaction(message: MessageRecord, author: String, emoji: String, added: Boolean) =
        sessions.sendTo(setOf(message.sender, message.recipient), WsFrames.reaction(message, author, emoji, added))

    /**
     * Fan-out for a deletion tombstone (ADR-0011, Phase 10): both
     * participants receive the frame, so each device can render the
     * placeholder immediately without a history refresh.
     */
    fun broadcastMessageDeleted(message: MessageRecord) =
        sessions.sendTo(setOf(message.sender, message.recipient), WsFrames.messageDeleted(message))

    /**
     * Fan-out for an edited message (Phase 17): both participants receive
     * the frame, so every device re-renders the bubble with the new content
     * and the "edited" hint immediately, without a history refresh.
     */
    fun broadcastMessageEdited(message: MessageRecord) =
        sessions.sendTo(setOf(message.sender, message.recipient), WsFrames.messageEdited(message))

    /**
     * Tells every device of both participants which messages the reader has
     * read (ADR-0010, multi-device Phase 13). The author turns them into
     * double ticks; the reader's *other* devices apply the receipt
     * idempotently so unread badges converge instead of waiting for their
     * own next mark response.
     */
    fun broadcastMessageRead(reader: String, author: String, mark: ReadMark) =
        sessions.sendTo(setOf(reader, author), WsFrames.messageRead(mark))
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfiguration(private val hub: WsHub) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(hub, "/v1/ws")
    }
}
