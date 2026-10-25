package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
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
data class IncomingMessage(val peer: String, val content: String, val createdAtEpochMs: Long)

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

    private val _incoming = MutableSharedFlow<IncomingMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingMessages: SharedFlow<IncomingMessage> = _incoming.asSharedFlow()

    /** The peer who is currently composing; cleared by a 5-second expiry. */
    private val _typingPeer = MutableStateFlow<String?>(null)
    val typingPeer: StateFlow<String?> = _typingPeer.asStateFlow()

    private var typingExpiryJob: Job? = null

    /** Peer whose chat the user is looking at; its messages are read on arrival. */
    @Volatile private var activeConversation: String? = null

    private val chatFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val historyErrors = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    init {
        scope.launch {
            chatCache.changes.collect { peer ->
                chatFlows[peer]?.value = chatCache.history(peer)
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
    fun seedForPreviews(me: MeResult, contacts: List<ContactDto>, unread: Map<String, Int> = emptyMap()) {
        _signIn.value = SignInState(success = true)
        _account.value = AccountState(me = me)
        _contacts.value = ContactsState(contacts = contacts)
        _unread.value = unread
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
            is RealtimeEvent.Unknown -> Unit
        }
    }

    private fun onMessageReaction(event: RealtimeEvent.MessageReaction) {
        chatCache.applyReaction(event.messageId, event.authorUsername, event.emoji, event.added)
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
        chatCache.applyReadReceipt(event.messageIds.toSet(), event.readAtEpochMs)
    }

    private fun onPresence(event: RealtimeEvent.Presence) {
        _contacts.update { state ->
            state.copy(
                contacts = state.contacts.map {
                    if (it.username == event.username) {
                        it.copy(presence = event.presence, presenceUpdatedAtEpochMs = event.updatedAtEpochMs)
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
            ),
            me,
        )
        val added = chatCache.append(peer, message)
        if (!added || message.fromMe) return
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

    fun isActiveConversation(peer: String): Boolean = activeConversation == peer

    /**
     * Clears the local unread badge of [peer]. This is a local counter only:
     * the server learns about the read state through [markConversationRead].
     */
    fun clearUnreadBadge(peer: String) {
        _unread.update { it - peer }
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
     * Only the foreground chat screen calls this, and only while the messages
     * are rendered. Notification handlers, background sync and cursor
     * catch-up must not, so a message is only "read" when a human could
     * actually see it.
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
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, content, System.currentTimeMillis(), SendState.PENDING, replyToId = replyToId))
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
     * Sends a failed message again with its original client key, so the
     * server stores it once even if the first attempt actually arrived.
     */
    fun resend(peer: String, message: ChatMessage) {
        if (!message.fromMe || !chatCache.markRetrying(message.clientKey)) return
        scope.launch(sessionJob) {
            if (message.type == "IMAGE" && message.mediaId != null) {
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

    fun history(peer: String): List<ChatMessage> = chatCache.history(peer)

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
        _typingPeer.value = null
        typingExpiryJob?.cancel()
        typingExpiryJob = null
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
