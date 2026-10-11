package com.patbaumgartner.android.stringtelephone.chat

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class SendState { QUEUED, SENT, FAILED }

data class ChatMessage(
    val serverId: String?,
    val clientKey: String,
    val fromMe: Boolean,
    val content: String,
    val sentAtEpochMs: Long,
    val state: SendState,
) {
    companion object {
        fun fromServer(m: com.patbaumgartner.android.stringtelephone.network.MessageResult, myUsername: String): ChatMessage = ChatMessage(
            serverId = m.id,
            clientKey = m.clientKey,
            fromMe = m.sender == myUsername,
            content = m.content,
            sentAtEpochMs = m.createdAt,
            state = SendState.SENT,
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

    @Synchronized
    fun append(peer: String, message: ChatMessage) {
        val list = byPeer.getOrPut(peer) { ArrayList() }
        if (list.any { it.clientKey == message.clientKey }) return
        list += message
        list.sortBy { it.sentAtEpochMs }
        emit(peer)
    }

    @Synchronized
    fun markSent(clientKey: String, serverId: String) {
        for (entry in byPeer) {
            val idx = entry.value.indexOfFirst { it.clientKey == clientKey }
            if (idx >= 0) {
                val m = entry.value[idx]
                entry.value[idx] = m.copy(serverId = serverId, state = SendState.SENT)
                emit(entry.key)
                return
            }
        }
    }

    @Synchronized
    fun markFailed(clientKey: String) {
        for (entry in byPeer) {
            val idx = entry.value.indexOfFirst { it.clientKey == clientKey }
            if (idx >= 0) {
                entry.value[idx] = entry.value[idx].copy(state = SendState.FAILED)
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
