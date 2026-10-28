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
    /** Message kind: TEXT (default), IMAGE or VOICE. */
    val type: String = "TEXT",
    /** Media id referenced by an IMAGE or VOICE message. */
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Reactions on this message (ADR-0011, Phase 8): one per author, keyed by username. */
    val reactions: Map<String, String> = emptyMap(),
    /** Message this one replies to; null when not a reply (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Tombstone (ADR-0011, Phase 10): non-null once deleted for both sides. */
    val deletedAtEpochMs: Long? = null,
    /** Who deleted the message; null while it exists. */
    val deletedBy: String? = null,
    /** Voice message duration in milliseconds (Phase 11); null for non-voice messages. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
    val waveform: String? = null,
) {
    /** True when deleted for both sides; the bubble renders a placeholder. */
    val deleted: Boolean get() = deletedAtEpochMs != null

    companion object {
        fun fromServer(m: com.patbaumgartner.android.stringtelephone.network.MessageResult, myUsername: String): ChatMessage = ChatMessage(
            serverId = m.id,
            clientKey = m.clientKey,
            fromMe = m.sender == myUsername,
            content = m.content,
            sentAtEpochMs = m.createdAt,
            state = if (m.readAt != null) SendState.READ else SendState.SENT,
            readAtEpochMs = m.readAt,
            type = m.type,
            mediaId = m.mediaId,
            width = m.width,
            height = m.height,
            reactions = m.reactions.associate { it.authorUsername to it.emoji },
            replyToId = m.replyToId,
            deletedAtEpochMs = m.deletedAt,
            deletedBy = m.deletedBy,
            durationMs = m.durationMs,
            waveform = m.waveform,
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

    /**
     * Applies a read receipt and returns the conversation peers whose cached
     * messages actually changed (Phase 13). Callers use it to converge
     * per-device state: clear the unread badge and cancel the shade
     * notification of a conversation another device just marked read.
     */
    fun applyReadReceiptForPeers(messageIds: Set<String>, readAtEpochMs: Long): Set<String> {
        if (messageIds.isEmpty()) return emptySet()
        val changedPeers = LinkedHashSet<String>()
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
                changedPeers += entry.key
                emit(entry.key)
            }
        }
        return changedPeers
    }

    /**
     * Applies a reaction change (ADR-0011, Phase 8) to the message with the
     * given [serverId]. Returns the peer to re-emit the changes flow on, or
     * null if the message is not cached.
     *
     * When [added] is true, the emoji is set or replaced for [author].
     * When false, the entry for [author] is removed.
     */
    @Synchronized
    fun applyReaction(serverId: String, author: String, emoji: String, added: Boolean): String? {
        for (entry in byPeer) {
            val index = entry.value.indexOfFirst { it.serverId == serverId }
            if (index >= 0) {
                val current = entry.value[index]
                val updated = if (added) {
                    current.copy(reactions = current.reactions + (author to emoji))
                } else {
                    current.copy(reactions = current.reactions - author)
                }
                if (updated != current) {
                    entry.value[index] = updated
                    emit(entry.key)
                }
                return entry.key
            }
        }
        return null
    }

    /**
     * Applies a deletion tombstone (ADR-0011, Phase 10) to the message with
     * the given [serverId]. Content stays in the local record so the reply
     * quote can still render the cached copy (ADR-0011), but the bubble
     * renders the placeholder. Returns the peer to re-emit on, or null if
     * the message is not cached.
     */
    @Synchronized
    fun applyDeleted(serverId: String, deletedAtEpochMs: Long, deletedBy: String?): String? {
        for (entry in byPeer) {
            val index = entry.value.indexOfFirst { it.serverId == serverId }
            if (index >= 0) {
                val current = entry.value[index]
                if (current.deleted) return entry.key
                entry.value[index] = current.copy(deletedAtEpochMs = deletedAtEpochMs, deletedBy = deletedBy)
                emit(entry.key)
                return entry.key
            }
        }
        return null
    }

    /** Local hide ("delete for me", ADR-0011, Phase 10): no server change. */
    @Synchronized
    fun hideLocally(peer: String, clientKey: String): Boolean {
        val list = byPeer[peer] ?: return false
        val index = list.indexOfFirst { it.clientKey == clientKey }
        if (index < 0) return false
        list.removeAt(index)
        emit(peer)
        return true
    }

    /**
     * The cached message a reply quotes (ADR-0011, Phase 9). The client
     * renders the quoted snapshot from the referenced message when it is in
     * the cache and renders nothing when it is not — no scroll-to-original
     * in this phase.
     */
    @Synchronized
    fun findQuotedMessage(peer: String, replyToId: String): ChatMessage? =
        byPeer[peer]?.firstOrNull { it.serverId == replyToId }

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
