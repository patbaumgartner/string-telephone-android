package com.patbaumgartner.android.stringtelephone.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Local delivery state of a message the user sent (ADR-0010). `PENDING` is
 * the optimistic state, `SENT` means the server stored it, `READ` means the
 * recipient read it in the conversation. `DELIVERED` is reserved for a future
 * durable delivery signal and is never produced today.
 */
enum class SendState { PENDING, SENT, READ, FAILED }

data class ChatMessage(
    val serverId: String?,
    val clientKey: String,
    val fromMe: Boolean,
    val content: String,
    val sentAtEpochMs: Long,
    val state: SendState,
    /** Server-assigned read timestamp for messages the peer read; null while unread. */
    val readAtEpochMs: Long? = null,
) {
    companion object {
        fun fromServer(m: com.patbaumgartner.android.stringtelephone.network.MessageResult, myUsername: String): ChatMessage = ChatMessage(
            serverId = m.id,
            clientKey = m.clientKey,
            fromMe = m.sender == myUsername,
            content = m.content,
            sentAtEpochMs = m.createdAt,
            state = if (m.readAt != null) SendState.READ else SendState.SENT,
            readAtEpochMs = m.readAt,
        )
    }
}

/**
 * In-memory conversation cache backed by server pagination on reconnect.
 * v1 keeps app-private in-memory state plus persisted server history; no
 * messages are written to shared or external storage.
 *
 * Mutations emit the affected peer on [changes] so repository-level
 * StateFlows can refresh; the cache itself stays framework-free.
 */
class ChatCache {
    private val byPeer = LinkedHashMap<String, MutableList<ChatMessage>>()
    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private fun emit(peer: String) {
        _changes.tryEmit(peer)
    }

    @Synchronized
    fun history(peer: String): List<ChatMessage> = byPeer[peer].orEmpty().toList()

    /** Adds [message] unless one with the same client key is cached; returns whether it was new. */
    @Synchronized
    fun append(peer: String, message: ChatMessage): Boolean {
        val list = byPeer.getOrPut(peer) { ArrayList() }
        val existing = list.indexOfFirst { it.clientKey == message.clientKey }
        if (existing >= 0) {
            // The server record wins for delivery state: it knows the durable id
            // and the read timestamp. A local READ is never downgraded, because
            // a history page fetched before the receipt may still say unread.
            val current = list[existing]
            val merged = current.copy(
                serverId = message.serverId ?: current.serverId,
                state = if (current.state == SendState.READ) SendState.READ else message.state,
                readAtEpochMs = message.readAtEpochMs ?: current.readAtEpochMs,
            )
            if (merged != current) {
                list[existing] = merged
                emit(peer)
            }
            return false
        }
        list += message
        list.sortBy { it.sentAtEpochMs }
        emit(peer)
        return true
    }

    @Synchronized
    fun markSent(clientKey: String, serverId: String) {
        for (entry in byPeer) {
            val idx = entry.value.indexOfFirst { it.clientKey == clientKey }
            if (idx >= 0) {
                val m = entry.value[idx]
                val state = if (m.state == SendState.READ) SendState.READ else SendState.SENT
                entry.value[idx] = m.copy(serverId = serverId, state = state)
                emit(entry.key)
                return
            }
        }
    }

    @Synchronized
    fun markFailed(clientKey: String) = updateState(clientKey, SendState.FAILED)

    /** Puts a failed message back into the outbox; returns false unless it had failed. */
    @Synchronized
    fun markRetrying(clientKey: String): Boolean {
        val failed = byPeer.values.any { list -> list.any { it.clientKey == clientKey && it.state == SendState.FAILED } }
        if (failed) updateState(clientKey, SendState.PENDING)
        return failed
    }

    /**
     * Applies a read receipt (ADR-0010): the peer read the messages with the
     * given server ids, so the local sent messages turn into double ticks.
     * Returns whether anything changed.
     */
    @Synchronized
    fun applyReadReceipt(messageIds: Set<String>, readAtEpochMs: Long): Boolean {
        if (messageIds.isEmpty()) return false
        var anyChanged = false
        for (entry in byPeer) {
            var changed = false
            for (index in entry.value.indices) {
                val message = entry.value[index]
                val id = message.serverId
                if (id == null || id !in messageIds) continue
                if (message.state == SendState.READ && message.readAtEpochMs == readAtEpochMs) continue
                entry.value[index] = message.copy(state = SendState.READ, readAtEpochMs = readAtEpochMs)
                changed = true
            }
            if (changed) {
                anyChanged = true
                emit(entry.key)
            }
        }
        return anyChanged
    }

    private fun updateState(clientKey: String, state: SendState) {
        for (entry in byPeer) {
            val idx = entry.value.indexOfFirst { it.clientKey == clientKey }
            if (idx >= 0) {
                entry.value[idx] = entry.value[idx].copy(state = state)
                emit(entry.key)
                return
            }
        }
    }

    @Synchronized
    fun clear() {
        val peers = byPeer.keys.toList()
        byPeer.clear()
        for (peer in peers) emit(peer)
    }
}
