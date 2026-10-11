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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun api(): CompanionApiClient? = apiProvider()

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
    fun seedForPreviews(me: MeResult, contacts: List<ContactDto>) {
        _signIn.value = SignInState(success = true)
        _account.value = AccountState(me = me)
        _contacts.value = ContactsState(contacts = contacts)
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
        if (event !is RealtimeEvent.NewMessage) return
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
            ),
            me,
        )
        chatCache.append(peer, message)
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

    fun send(peer: String, content: String, onResult: (Boolean) -> Unit) {
        scope.launch(sessionJob) {
            val client = apiProvider() ?: run { onResult(false); return@launch }
            val key = newMessageClientKey()
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, content, System.currentTimeMillis(), SendState.QUEUED))
            try {
                val sent = withContext(Dispatchers.IO) { client.sendMessage(peer, content, key) }
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

    fun history(peer: String): List<ChatMessage> = chatCache.history(peer)

    fun clearSession() {
        sessionJob.cancel()
        sessionJob = SupervisorJob(scope.coroutineContext[Job])
        stopRealtime()
        _account.value = AccountState()
        _contacts.value = ContactsState()
        _signIn.value = SignInState()
        drafts.clear()
        historyErrors.values.forEach { it.value = false }
        chatCache.clear()
        chatFlows.values.forEach { it.value = emptyList() }
    }
}
