package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ConversationStateDto
import com.patbaumgartner.android.stringtelephone.core.GroupDto
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.core.newMessageClientKey
import com.patbaumgartner.android.stringtelephone.network.ApiException
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.network.MeResult
import com.patbaumgartner.android.stringtelephone.network.MessageResult
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SignInError {
    INVALID_URL, INSECURE_URL, MISSING_CREDENTIALS, AUTHENTICATION, NETWORK, SERVER, PROTOCOL, IDENTITY,
}

data class AccountState(
    val me: MeResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ContactsState(
    val contacts: List<ContactDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class SignInState(
    val inProgress: Boolean = false,
    val success: Boolean = false,
    val error: SignInError? = null,
)

/** A message from [peer] that arrived over the realtime stream. */
data class IncomingMessage(
    val peer: String,
    val content: String,
    val createdAtEpochMs: Long,
    /** Author's username (Phase 19); group notifications name the sender per line. */
    val sender: String = "",
    /** Set when [peer] is a group id (Phase 19), so the shade shows the group name. */
    val groupId: String? = null,
)

/**
 * Single repository coordinating companion-API access and the local chat
 * cache. Network calls run on IO; results land in StateFlows.
 */
class StringTelephoneRepository(
    private val apiProvider: () -> CompanionApiClient?,
    private val chatCache: ChatCache,
    private val realtimeFactory: () -> RealtimeStream? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var realtime: RealtimeStream? = null
    private var sessionJob = SupervisorJob(scope.coroutineContext[Job])

    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _contacts = MutableStateFlow(ContactsState())
    val contacts: StateFlow<ContactsState> = _contacts.asStateFlow()

    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Unread incoming messages per peer; a peer's count resets when its chat is shown. */
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    // ── Conversation state (Phase 18: pin/mute/archive) ─────────────────────

    /** Pin/mute/archive per peer, as the server reports it; all devices agree. */
    private val _conversationStates = MutableStateFlow<Map<String, ConversationStateDto>>(emptyMap())

    /** The signed-in user's conversation state per peer (Phase 18). */
    val conversationStates: StateFlow<Map<String, ConversationStateDto>> = _conversationStates.asStateFlow()

    // ── Groups (Phase 19, ADR-0012) ─────────────────────────────────────────

    /**
     * The signed-in user's groups, newest activity first as the server
     * orders them. The chat cache addresses group conversations by group id,
     * mirroring the pairwise peer key (ADR-0012's client model).
     */
    private val _groups = MutableStateFlow<List<GroupDto>>(emptyList())
    val groups: StateFlow<List<GroupDto>> = _groups.asStateFlow()

    /** Members composing in each group; cleared by the same 5-second expiry as pairwise. */
    private val _groupTyping = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val groupTyping: StateFlow<Map<String, Set<String>>> = _groupTyping.asStateFlow()

    private val groupTypingJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _incoming = MutableSharedFlow<IncomingMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingMessages: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    /**
     * Peers whose messages were marked read by *another* device of this
     * account (Phase 13): the session layer cancels that conversation's
     * notification so the shade stays consistent across devices.
     */
    private val _readElsewhere = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val readElsewhere: SharedFlow<String> = _readElsewhere.asSharedFlow()

    /** The peer who is currently composing; cleared by a 5-second expiry. */
    private val _typingPeer = MutableStateFlow<String?>(null)
    val typingPeer: StateFlow<String?> = _typingPeer.asStateFlow()

    private var typingExpiryJob: Job? = null

    /** Peer whose chat the user is looking at; its messages are read on arrival. */
    @Volatile private var activeConversation: String? = null

    private val chatFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val historyErrors = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    private val _previews = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())

    init {
        scope.launch {
            chatCache.changes.collect { peer ->
                chatFlows[peer]?.value = chatCache.history(peer)
                // Phase 15: refresh this peer's preview alongside its history.
                // A peer with no cached message (everything hidden locally) is
                // removed so the row falls back to the presence subtitle.
                val latest = chatCache.preview(peer)
                _previews.update { current ->
                    when {
                        latest == null -> current - peer
                        current[peer] == latest -> current
                        else -> current + (peer to latest)
                    }
                }
            }
        }
    }

    fun apiBaseUrl(): String? = apiProvider()?.baseUrl()
    fun apiToken(): String? = apiProvider()?.authToken()

    /** Per-peer observable history; the cache emits a change token on every mutation. */
    fun chatFlow(peer: String): StateFlow<List<ChatMessage>> =
        chatFlows.getOrPut(peer) { MutableStateFlow(chatCache.history(peer)) }

    fun historyErrorFlow(peer: String): StateFlow<Boolean> =
        historyErrors.getOrPut(peer) { MutableStateFlow(false) }

    /** Draft text per conversation, kept for retry-friendly redrafts. */
    fun chatDraftFlow(peer: String): StateFlow<String> = MutableStateFlow(drafts[peer].orEmpty())

    fun saveChatDraft(peer: String, draft: String) {
        drafts[peer] = draft
    }

    /** Seeds observable state for previews and screenshot tests; not used in production flows. */
    fun seedForPreviews(me: MeResult, contacts: List<ContactDto>, unread: Map<String, Int> = emptyMap(), groups: List<GroupDto> = emptyList()) {
        _signIn.value = SignInState(success = true)
        _account.value = AccountState(me = me)
        _contacts.value = ContactsState(contacts = contacts)
        _unread.value = unread
        // Phase 18: the contact rows carry the pin/mute/archive state.
        _conversationStates.value = contacts.associate {
            it.username to ConversationStateDto(it.pinned, it.muted, it.archived)
        }
        // Phase 19: the screenshot fixtures seed the group list directly.
        _groups.value = groups
    }

    fun signInAndLoad(expectedUsername: String? = null) {
        if (_signIn.value.inProgress) return
        _signIn.value = SignInState(inProgress = true)
        scope.launch(sessionJob) {
            val client = apiProvider()
            if (client == null) {
                _signIn.value = SignInState(error = SignInError.MISSING_CREDENTIALS)
                return@launch
            }
            try {
                val me = withContext(Dispatchers.IO) { client.me() }
                ensureActive()
                if (expectedUsername != null && me.username != expectedUsername) {
                    _signIn.value = SignInState(error = SignInError.IDENTITY)
                    return@launch
                }
                _account.value = AccountState(me = me)
                _signIn.value = SignInState(inProgress = false, success = true)
                startRealtime()
                refreshContacts()
                refreshGroups()
            } catch (e: ApiException) {
                ensureActive()
                _signIn.value = SignInState(
                    error = when (e) {
                        ApiException.Auth, ApiException.Forbidden -> SignInError.AUTHENTICATION
                        ApiException.Network -> SignInError.NETWORK
                        is ApiException.Protocol -> SignInError.PROTOCOL
                        ApiException.Server, is ApiException.Unexpected -> SignInError.SERVER
                    },
                )
            }
        }
    }

    /**
     * Opens the realtime message socket for the signed-in account. Server-pushed
     * frames land in the same [ChatCache] as the REST paths, so the UI updates
     * without polling. Safe to call again; any previous socket is closed first.
     */
    fun startRealtime() {
        stopRealtime()
        val client = realtimeFactory() ?: return
        realtime = client
        scope.launch(sessionJob) {
            client.events.collect { event -> onRealtimeEvent(event) }
        }
        client.connect()
    }

    fun stopRealtime() {
        realtime?.disconnect()
        realtime = null
    }

    private fun onRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.NewMessage -> onNewMessage(event)
            is RealtimeEvent.Presence -> onPresence(event)
            is RealtimeEvent.MessageRead -> onMessageRead(event)
            is RealtimeEvent.Typing -> onTyping(event)
            is RealtimeEvent.MessageReaction -> onMessageReaction(event)
            is RealtimeEvent.MessageDeleted -> onMessageDeleted(event)
            is RealtimeEvent.MessageEdited -> onMessageEdited(event)
            is RealtimeEvent.GroupMessage -> onGroupMessage(event)
            is RealtimeEvent.GroupMessageRead -> onGroupMessageRead(event)
            is RealtimeEvent.GroupMessageReaction -> onGroupMessageReaction(event)
            is RealtimeEvent.GroupMessageDeleted -> onGroupMessageDeleted(event)
            is RealtimeEvent.GroupTyping -> onGroupTyping(event)
            is RealtimeEvent.Unknown -> Unit
        }
    }

    /** Applies a group reaction (Phase 19) under the group's conversation key. */
    private fun onGroupMessageReaction(event: RealtimeEvent.GroupMessageReaction) {
        if (!groups.value.any { it.id == event.groupId }) return
        chatCache.applyReaction(event.messageId, event.authorUsername, event.emoji, event.added)
    }

    /** Applies a group tombstone (Phase 19) under the group's conversation key. */
    private fun onGroupMessageDeleted(event: RealtimeEvent.GroupMessageDeleted) {
        if (!groups.value.any { it.id == event.groupId }) return
        chatCache.applyDeleted(event.messageId, event.deletedAtEpochMs, event.deletedBy)
    }

    private fun onMessageReaction(event: RealtimeEvent.MessageReaction) {
        chatCache.applyReaction(event.messageId, event.authorUsername, event.emoji, event.added)
    }

    private fun onMessageDeleted(event: RealtimeEvent.MessageDeleted) {
        chatCache.applyDeleted(event.messageId, event.deletedAtEpochMs, event.deletedBy)
    }

    /** Applies an edit (Phase 17) to the cached bubble: new content + hint timestamp. */
    private fun onMessageEdited(event: RealtimeEvent.MessageEdited) {
        chatCache.applyEdited(event.messageId, event.content, event.editedAtEpochMs)
    }

    /** Resets the 5-second UI expiry whenever a new typing signal arrives. */
    private fun onTyping(event: RealtimeEvent.Typing) {
        typingExpiryJob?.cancel()
        typingExpiryJob = null
        if (event.typing) {
            _typingPeer.value = event.peer
            typingExpiryJob = scope.launch {
                delay(TYPING_EXPIRY_MS)
                if (_typingPeer.value == event.peer) _typingPeer.value = null
            }
        } else if (_typingPeer.value == event.peer) {
            _typingPeer.value = null
        }
    }

    private fun onMessageRead(event: RealtimeEvent.MessageRead) {
        // With several devices signed in, this frame also arrives on devices
        // that did not perform the mark (the server fans out to both sides,
        // Phase 13). Applying the receipt clears this device's unread badge
        // and hands back the affected peers so the notification shade
        // converges instead of nagging about messages another device showed.
        val peers = chatCache.applyReadReceiptForPeers(event.messageIds.toSet(), event.readAtEpochMs)
        if (peers.isNotEmpty()) {
            _unread.update { counts -> counts - peers }
            for (peer in peers) _readElsewhere.tryEmit(peer)
        }
    }

    private fun onPresence(event: RealtimeEvent.Presence) {
        _contacts.update { state ->
            state.copy(
                contacts = state.contacts.map {
                    if (it.username == event.username) {
                        it.copy(
                            presence = event.presence,
                            presenceUpdatedAtEpochMs = event.updatedAtEpochMs,
                            lastSeenEpochMs = event.lastSeenEpochMs,
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun onNewMessage(event: RealtimeEvent.NewMessage) {
        val me = _account.value.me?.username ?: return
        val peer = if (event.sender == me) event.recipient else event.sender
        val message = ChatMessage.fromServer(
            MessageResult(
                id = event.id,
                sender = event.sender,
                recipient = event.recipient,
                content = event.content,
                createdAt = event.createdAtEpochMs,
                clientKey = event.clientKey,
                type = event.type,
                mediaId = event.mediaId,
                width = event.width,
                height = event.height,
                replyToId = event.replyToId,
                durationMs = event.durationMs,
                waveform = event.waveform,
            ),
            me,
        )
        val added = chatCache.append(peer, message)
        if (!added || message.fromMe) return
        // A new incoming message un-archives the conversation (Phase 18,
        // WhatsApp behaviour) so it reappears in the main list. The server
        // does the same on its side; this keeps an offline device in sync.
        if (_conversationStates.value[peer]?.archived == true) {
            _conversationStates.value = _conversationStates.value + (peer to ConversationStateDto(
                pinned = _conversationStates.value[peer]?.pinned ?: false,
                muted = _conversationStates.value[peer]?.muted ?: false,
                archived = false,
            ))
        }
        if (peer != activeConversation) {
            _unread.update { it + (peer to (it[peer] ?: 0) + 1) }
        }
        _incoming.tryEmit(IncomingMessage(peer, event.content, event.createdAtEpochMs))
    }

    /** Marks [peer] as the visible conversation (or none) and clears its unread count. */
    fun setActiveConversation(peer: String?) {
        activeConversation = peer
        if (peer != null) clearUnreadBadge(peer)
    }

    // ── Group events (Phase 19) ─────────────────────────────────────────────

    /**
     * A group message arrived: cache it under the group id, bump the unread
     * badge, and mirror the pairwise un-archive (Phase 18) for the group.
     * The group list itself is not refreshed here — membership changes are
     * rare and ride the next [refreshGroups].
     */
    private fun onGroupMessage(event: RealtimeEvent.GroupMessage) {
        val me = _account.value.me?.username ?: return
        val message = ChatMessage.fromServer(
            MessageResult(
                id = event.id,
                sender = event.sender,
                recipient = "",
                content = event.content,
                createdAt = event.createdAtEpochMs,
                clientKey = event.clientKey,
                type = event.type,
                mediaId = event.mediaId,
                width = event.width,
                height = event.height,
                replyToId = event.replyToId,
                durationMs = event.durationMs,
                waveform = event.waveform,
                groupId = event.groupId,
            ),
            me,
        )
        val key = event.groupId
        val added = chatCache.append(key, message)
        if (!added || message.fromMe) return
        if (key != activeConversation) {
            _unread.update { it + (key to (it[key] ?: 0) + 1) }
        }
        _incoming.tryEmit(IncomingMessage(key, event.content, event.createdAtEpochMs, sender = event.sender, groupId = event.groupId))
    }

    /**
     * A member read group messages: converge the read counter on every
     * device. The marking device also arrives here through the fan-out; the
     * reader-set makes the application idempotent.
     */
    private fun onGroupMessageRead(event: RealtimeEvent.GroupMessageRead) {
        chatCache.applyGroupRead(event.groupId, event.reader, event.messageIds.toSet(), event.readAtEpochMs)
    }

    /** Typing inside a group: a set of composing members with the pairwise expiry. */
    private fun onGroupTyping(event: RealtimeEvent.GroupTyping) {
        val me = _account.value.me?.username ?: return
        if (event.from == me) return
        val current = _groupTyping.value[event.groupId] ?: emptySet()
        val jobKey = "${event.groupId}:${event.from}"
        groupTypingJobs[jobKey]?.cancel()
        groupTypingJobs.remove(jobKey)
        if (event.typing) {
            _groupTyping.value = _groupTyping.value + (event.groupId to (current + event.from))
            groupTypingJobs[jobKey] = scope.launch {
                delay(TYPING_EXPIRY_MS)
                val now = _groupTyping.value[event.groupId] ?: emptySet()
                val updated = now - event.from
                _groupTyping.value = if (updated.isEmpty()) _groupTyping.value - event.groupId else _groupTyping.value + (event.groupId to updated)
                groupTypingJobs.remove(jobKey)
            }
        } else {
            val updated = current - event.from
            _groupTyping.value = if (updated.isEmpty()) _groupTyping.value - event.groupId else _groupTyping.value + (event.groupId to updated)
        }
    }

    /** Reloads the group list from the server (sign-in, pull-to-refresh). */
    fun refreshGroups() {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                val list = withContext(Dispatchers.IO) { client.groups() }
                ensureActive()
                _groups.value = list
            } catch (_: ApiException) {
                ensureActive()
            }
        }
    }

    /** Creates a group (the creator becomes ADMIN) and refreshes the list. */
    fun createGroup(name: String, members: List<String>, onResult: (GroupDto?) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(null); return@launch }
            try {
                val created = withContext(Dispatchers.IO) { client.createGroup(name, members) }
                ensureActive()
                _groups.value = listOf(created) + _groups.value
                onResult(created)
            } catch (_: ApiException) {
                ensureActive()
                onResult(null)
            }
        }
    }

    /** Renames a group (ADMIN only); the list and detail re-render from the result. */
    fun renameGroup(groupId: String, name: String, onResult: (GroupDto?) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(null); return@launch }
            try {
                val renamed = withContext(Dispatchers.IO) { client.renameGroup(groupId, name) }
                ensureActive()
                replaceGroup(renamed)
                onResult(renamed)
            } catch (_: ApiException) {
                ensureActive()
                onResult(null)
            }
        }
    }

    /** Adds one member (ADMIN only). */
    fun addGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(null); return@launch }
            try {
                val updated = withContext(Dispatchers.IO) { client.addGroupMember(groupId, username) }
                ensureActive()
                replaceGroup(updated)
                onResult(updated)
            } catch (_: ApiException) {
                ensureActive()
                onResult(null)
            }
        }
    }

    /**
     * Removes a member: the ADMIN removes others, a MEMBER removes
     * themselves (leave). The acting user's list loses the group when they
     * were the one removed.
     */
    fun removeGroupMember(groupId: String, username: String, onResult: (GroupDto?) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(null); return@launch }
            try {
                val updated = withContext(Dispatchers.IO) { client.removeGroupMember(groupId, username) }
                ensureActive()
                replaceGroup(updated)
                onResult(updated)
            } catch (_: ApiException) {
                ensureActive()
                // A removed member no longer sees the group at all (404).
                _groups.value = _groups.value.filter { it.id != groupId }
                onResult(null)
            }
        }
    }

    /** Disbands the group (ADMIN only); everyone's list loses it on next load. */
    fun disbandGroup(groupId: String, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(false); return@launch }
            try {
                withContext(Dispatchers.IO) { client.disbandGroup(groupId) }
                ensureActive()
                _groups.value = _groups.value.filter { it.id != groupId }
                onResult(true)
            } catch (_: ApiException) {
                ensureActive()
                onResult(false)
            }
        }
    }

    /** Replaces one group in the list, keeping the newest-activity order. */
    private fun replaceGroup(updated: GroupDto) {
        _groups.value = _groups.value.map { if (it.id == updated.id) updated else it }
    }

    /** Loads one group conversation's history under its group id key. */
    fun loadGroupHistory(groupId: String, myUsername: String) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            val error = historyErrors.getOrPut(groupId) { MutableStateFlow(false) }
            error.value = false
            try {
                val page = withContext(Dispatchers.IO) { client.groupMessages(groupId, null) }
                ensureActive()
                for (m in page.messages) {
                    chatCache.append(groupId, ChatMessage.fromServer(m, myUsername))
                }
            } catch (_: ApiException) {
                ensureActive()
                error.value = true
            }
        }
    }

    /**
     * Sends one group text message (Phase 19): optimistic bubble under the
     * group id key, then the REST send; identical mechanics to pairwise.
     */
    fun sendGroup(groupId: String, content: String, replyToId: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val me = _account.value.me?.username ?: ""
            val key = newMessageClientKey()
            chatCache.append(groupId, ChatMessage(null, key, fromMe = true, sender = me, content, System.currentTimeMillis(), SendState.PENDING, replyToId = replyToId))
            val client = apiProvider() ?: run { chatCache.markFailed(key); onResult(false); return@launch }
            try {
                val sent = withContext(Dispatchers.IO) { client.sendGroupMessage(groupId, content, key, replyToId) }
                ensureActive()
                chatCache.markSent(key, sent.id)
                onResult(true)
            } catch (_: ApiException) {
                ensureActive()
                chatCache.markFailed(key)
                onResult(false)
            }
        }
    }

    /**
     * Marks group messages read (Phase 19): foreground group chat screen
     * only, exactly like the pairwise mark (ADR-0010).
     */
    fun markGroupConversationRead(groupId: String, upToMessageId: String?) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                val mark = withContext(Dispatchers.IO) { client.markGroupRead(groupId, upToMessageId) }
                ensureActive()
                if (mark.messageIds.isNotEmpty()) {
                    val me = _account.value.me?.username ?: return@launch
                    chatCache.applyGroupRead(groupId, me, mark.messageIds.toSet(), mark.readAt)
                }
            } catch (_: ApiException) {
                ensureActive()
            }
        }
    }

    /** Toggles the signed-in user's reaction on a group message (Phase 19). */
    fun toggleGroupReaction(groupId: String, message: ChatMessage, emoji: String) {
        val me = _account.value.me?.username ?: return
        val serverId = message.serverId ?: return
        val hadMine = message.reactions.containsKey(me)
        val adding = !hadMine || message.reactions[me] != emoji
        chatCache.applyReaction(serverId, me, emoji, added = adding)
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    if (adding) client.setGroupReaction(groupId, serverId, emoji) else client.removeGroupReaction(groupId, serverId)
                }
                ensureActive()
            } catch (_: ApiException) {
                ensureActive()
                chatCache.applyReaction(serverId, me, emoji, added = !adding)
            }
        }
    }

    /** Deletes a group message for all members (author or admin, Phase 19). */
    fun deleteGroupMessage(groupId: String, message: ChatMessage) {
        val me = _account.value.me?.username ?: return
        val serverId = message.serverId ?: return
        chatCache.applyDeleted(serverId, System.currentTimeMillis(), me)
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                withContext(Dispatchers.IO) { client.deleteGroupMessage(groupId, serverId) }
                ensureActive()
            } catch (_: ApiException) {
                ensureActive()
                chatCache.applyDeleted(serverId, 0L, null)
                loadGroupHistory(groupId, me)
            }
        }
    }

    /** Sends the group typing signal (Phase 19); same best-effort rules. */
    fun notifyGroupTyping(groupId: String) {
        realtime?.sendGroupTyping(groupId, true)
    }

    fun sendGroupTypingStop(groupId: String) {
        realtime?.sendGroupTyping(groupId, false)
    }

    fun isActiveConversation(peer: String): Boolean = activeConversation == peer

    /**
     * Clears the local unread badge of [peer]. This is a local counter only:
     * the server learns about the read state through [markConversationRead].
     */
    fun clearUnreadBadge(peer: String) {
        _unread.update { it - peer }
    }

    /**
     * Updates the signed-in user's pin/mute/archive state for [peer]
     * (Phase 18). Optimistic: the local map moves first so the list reorders
     * instantly; a failure rolls back to the previous flags and re-throws so
     * the caller can surface it.
     */
    fun updateConversationState(peer: String, pinned: Boolean?, muted: Boolean?, archived: Boolean?) {
        val previous = _conversationStates.value[peer] ?: ConversationStateDto()
        val optimistic = ConversationStateDto(
            pinned = pinned ?: previous.pinned,
            muted = muted ?: previous.muted,
            archived = archived ?: previous.archived,
        )
        _conversationStates.value = _conversationStates.value + (peer to optimistic)
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                val saved = withContext(Dispatchers.IO) { client.updateConversationState(peer, pinned, muted, archived) }
                ensureActive()
                _conversationStates.value = _conversationStates.value + (peer to saved)
            } catch (e: ApiException) {
                ensureActive()
                _conversationStates.value = _conversationStates.value + (peer to previous)
                throw e
            }
        }
    }

    /** True when [peer] is muted (Phase 18): notifications suppressed, badges stay. */
    fun isMuted(peer: String): Boolean = _conversationStates.value[peer]?.muted ?: false

    /** Applies a server-side state snapshot (e.g. from contacts refresh). */
    fun seedConversationStates(states: Map<String, ConversationStateDto>) {
        _conversationStates.value = states
    }

    fun reportSignInError(error: SignInError) {
        _signIn.value = SignInState(error = error)
    }

    fun refreshContacts() {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            _contacts.value = _contacts.value.copy(loading = true, error = null)
            try {
                val list = withContext(Dispatchers.IO) { client.contacts() }
                ensureActive()
                _contacts.value = ContactsState(contacts = list)
                // The contact rows carry the caller's pin/mute/archive state
                // (Phase 18), so one request syncs both.
                _conversationStates.value = list.associate {
                    it.username to ConversationStateDto(it.pinned, it.muted, it.archived)
                }
            } catch (e: ApiException) {
                ensureActive()
                _contacts.value = _contacts.value.copy(loading = false, error = e.message)
            }
        }
    }

    /**
     * Loads the conversation history from the server.
     *
     * This never marks anything read: a cursor catch-up runs while the chat
     * may be closed, and only the foreground chat screen may mark read
     * (ADR-0010).
     */
    fun loadHistory(peer: String, myUsername: String) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            val error = historyErrors.getOrPut(peer) { MutableStateFlow(false) }
            error.value = false
            try {
                val page = withContext(Dispatchers.IO) { client.messages(peer, null) }
                ensureActive()
                for (m in page.messages) {
                    chatCache.append(peer, ChatMessage.fromServer(m, myUsername))
                }
            } catch (_: ApiException) {
                ensureActive()
                error.value = true
            }
        }
    }

    /**
     * Marks the peer's messages as read on the server (ADR-0010).
     *
     * Called from the foreground chat screen while messages are rendered and
     * from the notification's "Mark as read" action (Phase 14) — both are
     * explicit user-visible actions. Notification arrival, background sync
     * and cursor catch-up still never mark anything read on their own.
     */
    fun markConversationRead(peer: String, upToMessageId: String?) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                val mark = withContext(Dispatchers.IO) { client.markRead(peer, upToMessageId) }
                ensureActive()
                if (mark.messageIds.isNotEmpty()) {
                    chatCache.applyReadReceipt(mark.messageIds.toSet(), mark.readAt)
                }
            } catch (_: ApiException) {
                // A failed receipt is not worth surfacing: the next render retries.
                ensureActive()
            }
        }
    }

    fun uploadMedia(peer: String, bytes: ByteArray, contentType: String, fileName: String): com.patbaumgartner.android.stringtelephone.core.MediaRecordDto {
        val client = apiProvider() ?: throw IllegalStateException("no api")
        return client.uploadMedia(peer, bytes, contentType, fileName)
    }

    fun send(peer: String, content: String, replyToId: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val key = newMessageClientKey()
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, sender = _account.value.me?.username.orEmpty(), content, System.currentTimeMillis(), SendState.PENDING, replyToId = replyToId))
            onResult(deliver(peer, content, key, replyToId))
        }
    }

    fun sendImage(peer: String, mediaId: String, width: Int?, height: Int?, replyToId: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val key = newMessageClientKey()
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, content = "", sentAtEpochMs = System.currentTimeMillis(), state = SendState.PENDING, type = "IMAGE", mediaId = mediaId, width = width, height = height, replyToId = replyToId))
            onResult(deliverImage(peer, mediaId, key, replyToId))
        }
    }

    /**
     * Sends a voice message (Phase 11): optimistic VOICE bubble, then the
     * upload + send dance. The reply banner, if any, is consumed by the
     * caller (the ViewModel clears it before recording ends).
     */
    fun sendVoice(peer: String, mediaId: String, durationMs: Int, waveform: String, replyToId: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val key = newMessageClientKey()
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, content = "", sentAtEpochMs = System.currentTimeMillis(), state = SendState.PENDING, type = "VOICE", mediaId = mediaId, durationMs = durationMs, waveform = waveform, replyToId = replyToId))
            onResult(deliverVoice(peer, mediaId, key, durationMs, waveform, replyToId))
        }
    }

    /**
     * Uploads recorded audio and sends the VOICE message (Phase 11). Kept in
     * the repository so a retry re-runs the exact same path; the composer
     * hands over finished recording bytes only. Once the upload returns, the
     * send goes through [sendVoice] so the optimistic bubble and the failed-
     * message retry work exactly like text and images.
     */
    fun sendVoiceRecording(peer: String, bytes: ByteArray, contentType: String, fileName: String, durationMs: Int, waveform: String, replyToId: String? = null, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            try {
                val media = withContext(Dispatchers.IO) { uploadMedia(peer, bytes, contentType, fileName) }
                ensureActive()
                sendVoice(peer, media.id, durationMs, waveform, replyToId, onResult)
            } catch (e: Exception) {
                ensureActive()
                onResult(false)
            }
        }
    }

    /**
     * Sends a failed message again with its original client key, so the
     * server stores it once even if the first attempt actually arrived.
     */
    fun resend(peer: String, message: ChatMessage) {
        if (!message.fromMe || !chatCache.markRetrying(message.clientKey)) return
        scope.launch(sessionJob) {
            if (message.type == "VOICE" && message.mediaId != null) {
                deliverVoice(peer, message.mediaId, message.clientKey, message.durationMs ?: 0, message.waveform ?: "", message.replyToId)
            } else if (message.type == "IMAGE" && message.mediaId != null) {
                deliverImage(peer, message.mediaId, message.clientKey, message.replyToId)
            } else {
                deliver(peer, message.content, message.clientKey, message.replyToId)
            }
        }
    }

    private suspend fun deliver(peer: String, content: String, key: String, replyToId: String? = null): Boolean {
        val client = apiProvider() ?: run { chatCache.markFailed(key); return false }
        return try {
            val sent = withContext(Dispatchers.IO) { client.sendMessage(peer, content, key, replyToId) }
            currentCoroutineContext().ensureActive()
            chatCache.markSent(key, sent.id)
            true
        } catch (_: ApiException) {
            currentCoroutineContext().ensureActive()
            chatCache.markFailed(key)
            false
        }
    }

    private suspend fun deliverImage(peer: String, mediaId: String, key: String, replyToId: String? = null): Boolean {
        val client = apiProvider() ?: run { chatCache.markFailed(key); return false }
        return try {
            val sent = withContext(Dispatchers.IO) { client.sendMedia(peer, mediaId, key, replyToId = replyToId) }
            currentCoroutineContext().ensureActive()
            chatCache.markSent(key, sent.id)
            true
        } catch (_: ApiException) {
            currentCoroutineContext().ensureActive()
            chatCache.markFailed(key)
            false
        }
    }

    private suspend fun deliverVoice(peer: String, mediaId: String, key: String, durationMs: Int, waveform: String, replyToId: String? = null): Boolean {
        val client = apiProvider() ?: run { chatCache.markFailed(key); return false }
        return try {
            val sent = withContext(Dispatchers.IO) { client.sendVoice(peer, mediaId, key, durationMs, waveform, replyToId) }
            currentCoroutineContext().ensureActive()
            chatCache.markSent(key, sent.id)
            true
        } catch (_: ApiException) {
            currentCoroutineContext().ensureActive()
            chatCache.markFailed(key)
            false
        }
    }

    /**
     * Toggles the signed-in user's reaction on a message (ADR-0011, Phase 8).
     * Tapping a chip the user already reacted to removes the reaction; any
     * other chip adds or replaces it with that emoji (one per author, the
     * server upserts).
     *
     * The chip updates optimistically; the REST response and the realtime
     * fan-out confirm it. A failed request rolls the optimistic chip back.
     */
    fun toggleReaction(peer: String, message: ChatMessage, emoji: String) {
        val me = _account.value.me?.username ?: return
        val serverId = message.serverId ?: return
        val hadMine = message.reactions.containsKey(me)
        val adding = !hadMine || message.reactions[me] != emoji
        chatCache.applyReaction(serverId, me, emoji, added = adding)
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                withContext(Dispatchers.IO) {
                    if (adding) client.setReaction(peer, serverId, emoji) else client.removeReaction(peer, serverId)
                }
                ensureActive()
            } catch (_: ApiException) {
                ensureActive()
                // Roll the optimistic chip back; a later history load reconciles.
                chatCache.applyReaction(serverId, me, emoji, added = !adding)
            }
        }
    }

    /**
     * Deletes a message for both sides (ADR-0011, Phase 10): tombstones it
     * optimistically, then asks the server to clear the content. A failed
     * request rolls the tombstone back; a later history load reconciles.
     */
    fun deleteMessage(peer: String, message: ChatMessage) {
        val me = _account.value.me?.username ?: return
        val serverId = message.serverId ?: return
        chatCache.applyDeleted(serverId, System.currentTimeMillis(), me)
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                withContext(Dispatchers.IO) { client.deleteMessage(peer, serverId) }
                ensureActive()
            } catch (_: ApiException) {
                ensureActive()
                // Roll the optimistic tombstone back; history load reconciles.
                chatCache.applyDeleted(serverId, 0L, null)
                // Re-fetching is the honest reconciliation for a tombstone
                // that may or may not have landed: reload this conversation.
                loadHistory(peer, me)
            }
        }
    }

    /**
     * Hides a message locally only ("delete for me", ADR-0011, Phase 10):
     * no server change, so the peer still sees it and a history reload
     * brings it back.
     */
    fun deleteForMe(peer: String, message: ChatMessage) {
        chatCache.hideLocally(peer, message.clientKey)
    }

    /**
     * Edits a text message the user authored (Phase 17): optimistic content
     * swap in the cache, then the PUT. A failure rolls the content back —
     * history reload reconciles, mirroring the delete path.
     */
    fun editMessage(peer: String, message: ChatMessage, newContent: String) {
        val serverId = message.serverId ?: return
        chatCache.applyEdited(serverId, newContent, System.currentTimeMillis())
        scope.launch(sessionJob) {
            val client = apiProvider() ?: return@launch
            try {
                withContext(Dispatchers.IO) { client.editMessage(peer, serverId, newContent) }
                ensureActive()
            } catch (_: ApiException) {
                ensureActive()
                // Roll the optimistic edit back; history load reconciles.
                chatCache.applyEdited(serverId, message.content, 0L)
                loadHistory(peer, _account.value.me?.username ?: return@launch)
            }
        }
    }

    fun history(peer: String): List<ChatMessage> = chatCache.history(peer)

    /**
     * Full-text search in one conversation (Phase 16). Results come back
     * newest-first from the server; the UI shows them newest-first and jumps
     * to the tapped message in the timeline. The query must be non-blank;
     * the scope is the authenticated pair, enforced server-side.
     */
    /**
     * Full-text search in one conversation (Phase 16). Results come back
     * newest-first from the server; the UI shows them newest-first and jumps
     * to the tapped message in the timeline. The query must be non-blank;
     * the scope is the authenticated pair, enforced server-side. All pages
     * are fetched before returning — search results are a one-shot list,
     * not an incremental stream.
     */
    /**
     * Full-text search in one conversation (Phase 16). Results come back
     * newest-first from the server; the UI shows them newest-first and jumps
     * to the tapped message in the timeline. The query must be non-blank;
     * the scope is the authenticated pair, enforced server-side. All pages
     * are fetched before returning — search results are a one-shot list,
     * not an incremental stream.
     */
    suspend fun search(peer: String, query: String): List<ChatMessage> {
        val client = apiProvider() ?: return emptyList()
        val all = ArrayList<ChatMessage>()
        var cursor: String? = null
        while (true) {
            val page = runCatching { withContext(Dispatchers.IO) { client.search(peer, query, cursor) } }.getOrNull() ?: break
            for (m in page.messages) all += ChatMessage.fromServer(m, _account.value.me?.username ?: "")
            cursor = page.nextCursor ?: break
        }
        return all
    }

    /**
     * Conversation previews (Phase 15): one [ChatMessage] per peer — the
     * newest — refreshed on every cache change. Peers without messages are
     * absent, so the UI falls back to the presence subtitle.
     */
    val previews: StateFlow<Map<String, ChatMessage>> = _previews.asStateFlow()

    /** Filters contacts by name locally (Phase 15); case-insensitive contains. */
    fun filterContacts(contacts: List<ContactDto>, query: String): List<ContactDto> =
        if (query.isBlank()) contacts
        else contacts.filter {
            it.displayName.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true)
        }

    /**
     * Applies the Phase 18 list rules to one contact list (framework-free,
     * unit-tested):
     *  - pinned conversations float to the top, newest activity first
     *    (the server list already arrives newest-activity-first);
     *  - archived conversations are dropped — the caller renders them in
     *    the separate "Archived" section via [archivedContacts].
     *
     * Within each group the relative order is preserved.
     */
    fun orderedContacts(
        contacts: List<ContactDto>,
        states: Map<String, ConversationStateDto>,
    ): Pair<List<ContactDto>, List<ContactDto>> {
        val stateOf = { contact: ContactDto ->
            states[contact.username] ?: ConversationStateDto(contact.pinned, contact.muted, contact.archived)
        }
        val archived = contacts.filter { stateOf(it).archived }
        val visible = contacts.filter { !stateOf(it).archived }
        val pinned = visible.filter { stateOf(it).pinned }
        val rest = visible.filter { !stateOf(it).pinned }
        return Pair(pinned + rest, archived)
    }

    /**
     * Sends the ephemeral typing signal (Phase 7). Best effort over the live
     * stream: no retry, no persistence, and a dropped frame is invisible.
     */
    fun sendTyping(typing: Boolean) {
        if (typing) return
        // A "stop" also clears the local expiry so a stale indicator cannot linger.
        typingExpiryJob?.cancel()
        typingExpiryJob = null
        _typingPeer.value = null
        realtime?.sendTyping(false)
    }

    fun notifyTyping() {
        realtime?.sendTyping(true)
    }

    fun clearSession() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        stopRealtime()
        _account.value = AccountState()
        _contacts.value = ContactsState()
        _signIn.value = SignInState()
        _unread.value = emptyMap()
        _previews.value = emptyMap()
        _typingPeer.value = null
        typingExpiryJob?.cancel()
        typingExpiryJob = null
        _groups.value = emptyList()
        _groupTyping.value = emptyMap()
        groupTypingJobs.values.forEach { it.cancel() }
        groupTypingJobs.clear()
        activeConversation = null
        drafts.clear()
        historyErrors.values.forEach { it.value = false }
        chatCache.clear()
        chatFlows.values.forEach { it.value = emptyList() }
    }

    companion object {
        /** Typing indicators expire when no refresh arrives within this window. */
        const val TYPING_EXPIRY_MS = 5_000L
    }
}
