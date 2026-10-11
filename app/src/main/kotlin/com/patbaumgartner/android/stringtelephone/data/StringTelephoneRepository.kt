package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.newMessageClientKey
import com.patbaumgartner.android.stringtelephone.network.ApiException
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.network.MeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val error: String? = null,
)

/**
 * Single repository coordinating companion-API access and the local chat
 * cache. Network calls run on IO; results land in StateFlows.
 */
class StringTelephoneRepository(
    private val apiProvider: () -> CompanionApiClient?,
    private val chatCache: ChatCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _account = MutableStateFlow(AccountState())
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _contacts = MutableStateFlow(ContactsState())
    val contacts: StateFlow<ContactsState> = _contacts.asStateFlow()

    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val chatFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<ChatMessage>>>()
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    fun signInAndLoad() {
        scope.launch {
            val client = apiProvider()
            if (client == null) {
                _signIn.value = SignInState(inProgress = false, error = "not signed in")
                return@launch
            }
            _signIn.value = _signIn.value.copy(inProgress = true, error = null)
            try {
                val me = withContext(Dispatchers.IO) { client.me() }
                _account.value = AccountState(me = me)
                _signIn.value = SignInState(inProgress = false, success = true)
                refreshContacts()
            } catch (e: ApiException) {
                _signIn.value = _signIn.value.copy(inProgress = false, error = e.message)
            }
        }
    }

    fun reportSignInError(message: String) {
        _signIn.value = SignInState(inProgress = false, error = message)
    }

    fun refreshContacts() {
        scope.launch {
            val client = apiProvider() ?: return@launch
            _contacts.value = _contacts.value.copy(loading = true, error = null)
            try {
                val list = withContext(Dispatchers.IO) { client.contacts() }
                _contacts.value = ContactsState(contacts = list)
            } catch (e: ApiException) {
                _contacts.value = _contacts.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun loadHistory(peer: String, myUsername: String) {
        scope.launch {
            val client = apiProvider() ?: return@launch
            try {
                val page = withContext(Dispatchers.IO) { client.messages(peer, null) }
                for (m in page.messages) {
                    chatCache.append(peer, ChatMessage.fromServer(m, myUsername))
                }
            } catch (_: ApiException) {
                // leave cache as-is; UI shows stale data with an error hint
            }
        }
    }

    fun send(peer: String, content: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val client = apiProvider() ?: run { onResult(false); return@launch }
            val key = newMessageClientKey()
            chatCache.append(peer, ChatMessage(null, key, fromMe = true, content, System.currentTimeMillis(), SendState.QUEUED))
            try {
                val sent = withContext(Dispatchers.IO) { client.sendMessage(peer, content, key) }
                chatCache.markSent(key, sent.id)
                onResult(true)
            } catch (_: ApiException) {
                chatCache.markFailed(key)
                onResult(false)
            }
        }
    }

    fun history(peer: String): List<ChatMessage> = chatCache.history(peer)

    fun clearSession() {
        _account.value = AccountState()
        _contacts.value = ContactsState()
        _signIn.value = SignInState()
        chatCache.clear()
    }
}
