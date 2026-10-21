package com.patbaumgartner.stringtelephone

import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of the live realtime sessions, one bucket per user.
 *
 * It carries no dependencies on purpose: the WebSocket handler needs it to
 * deliver frames, and the presence service needs it to know who is online.
 * Keeping the registry in its own type keeps those two dependencies acyclic,
 * so neither side has to reach back into the other.
 */
class WsSessions {
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>>()
    private val log = org.slf4j.LoggerFactory.getLogger(WsSessions::class.java)

    fun isConnected(username: String): Boolean = sessions[username]?.isNotEmpty() == true

    fun hasSessions(): Boolean = sessions.values.any { it.isNotEmpty() }

    /** Registers [session] for [username]; returns true when it is their first session. */
    fun open(username: String, session: WebSocketSession): Boolean = synchronized(this) {
        val userSessions = sessions.computeIfAbsent(username) { ConcurrentHashMap() }
        val first = userSessions.isEmpty()
        userSessions[session.id] = ConcurrentWebSocketSessionDecorator(session, 5_000, 256 * 1024)
        first
    }

    /** Removes [session]; returns true when it was the user's last session. */
    fun close(username: String, session: WebSocketSession): Boolean = synchronized(this) {
        val userSessions = sessions[username] ?: return false
        val removed = userSessions.remove(session.id) != null
        val last = removed && userSessions.isEmpty()
        if (last) sessions.remove(username, userSessions)
        last
    }

    /** Sends [payload] to every session of [username]. */
    fun send(username: String, payload: String) {
        sessions[username]?.values?.forEach { send(username, it, payload) }
    }

    /** Sends [payload] to every session of the given users. */
    fun sendTo(recipients: Collection<String>, payload: String) {
        for (username in recipients) send(username, payload)
    }

    /** Sends [payload] to every session except those of [except]. */
    fun broadcast(except: String?, payload: String) {
        for ((username, userSessions) in sessions) {
            if (username == except) continue
            userSessions.values.forEach { send(username, it, payload) }
        }
    }

    /** Closes every session of [username]; used when an account is disabled. */
    fun disconnect(username: String) {
        sessions.remove(username)?.values?.forEach { session ->
            if (session.isOpen) session.close(CloseStatus.POLICY_VIOLATION)
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
