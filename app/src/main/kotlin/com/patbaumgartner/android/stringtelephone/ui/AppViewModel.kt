package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.patbaumgartner.android.stringtelephone.BuildConfig
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallForegroundService
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.PresenceStatus
import com.patbaumgartner.android.stringtelephone.core.ServerUrlValidation
import com.patbaumgartner.android.stringtelephone.core.validateServerUrl
import com.patbaumgartner.android.stringtelephone.data.AccountState
import com.patbaumgartner.android.stringtelephone.data.ContactsState
import com.patbaumgartner.android.stringtelephone.data.SignInState
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.notify.AppNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which screen is showing; navigation is a plain sealed list, not Navigation-Compose. */
sealed interface AppScreen {
    data object Onboarding : AppScreen
    data object Contacts : AppScreen
    data class Chat(val peer: String) : AppScreen
    data class Call(val peer: String, val incoming: Boolean) : AppScreen
    data object Settings : AppScreen
}

/**
 * Single ViewModel owning navigation state and user actions, following the
 * reference project's one-ViewModel convention. Screens take (state, this).
 */
class AppViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repository = container.repository
    private val engine = container.callEngine

    var serverUrlInput by mutableStateOf(BuildConfig.DEV_SERVER_URL)
        private set
    var usernameInput by mutableStateOf(BuildConfig.DEV_USERNAME)
        private set
    var passwordInput by mutableStateOf(BuildConfig.DEV_PASSWORD)
        private set
    var chatInput by mutableStateOf("")
        private set

    private val _screen = MutableStateFlow(currentStartScreen())
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionInfo(remoteUser = "", state = CallState.IDLE))
    val callState: StateFlow<CallSessionInfo> = _callState.asStateFlow()

    /** Peer of the incoming call that has not been answered yet; used for missed-call detection. */
    private var awaitingIncomingPeer: String? = null

    init {
        viewModelScope.launch {
            engine.events.collect { event -> onCallEvent(event) }
        }
        viewModelScope.launch {
            engine.state.collect { info -> _callState.value = info }
        }
        if (isSignedIn()) {
            repository.signInAndLoad()
            repository.refreshContacts()
        }
    }

    private fun onCallEvent(event: CallEvent) {
        when (event) {
            is CallEvent.IncomingCall -> {
                _screen.value = AppScreen.Call(event.remoteUser, incoming = true)
                awaitingIncomingPeer = event.remoteUser
            }
            is CallEvent.CallConnected -> {
                awaitingIncomingPeer = null
                CallForegroundService.start(container.context, event.remoteUser)
            }
            is CallEvent.CallEnded, is CallEvent.CallFailed -> {
                // An incoming call that ended without being answered is a missed call.
                awaitingIncomingPeer?.let { peer ->
                    AppNotifier.notifyMissedCall(
                        container.context,
                        peer,
                        displayNameOf(peer, repository.contacts.value.contacts),
                    )
                    awaitingIncomingPeer = null
                }
                CallForegroundService.stop(container.context)
                if (_screen.value is AppScreen.Call) _screen.value = AppScreen.Contacts
            }
            else -> Unit
        }
    }

    fun onServerUrlChanged(value: String) {
        serverUrlInput = value
    }

    fun onUsernameChanged(value: String) {
        usernameInput = value
    }

    fun onPasswordChanged(value: String) {
        passwordInput = value
    }

    fun onChatInputChanged(value: String) {
        chatInput = value
    }

    fun signIn() {
        val urlValidation = validateServerUrl(serverUrlInput)
        if (urlValidation !is ServerUrlValidation.Valid) {
            repository.reportSignInError("Enter a valid https:// server URL")
            return
        }
        if (usernameInput.isBlank() || passwordInput.isBlank()) {
            repository.reportSignInError("Username and password are required")
            return
        }
        container.credentialStore.save(
            serverUrl = urlValidation.normalized,
            username = usernameInput.trim(),
            apiToken = passwordInput,
            sipSecret = passwordInput,
        )
        repository.signInAndLoad()
        _screen.value = AppScreen.Contacts
    }

    fun signInState(): StateFlow<SignInState> = repository.signIn

    fun accountState(): StateFlow<AccountState> = repository.account

    fun contactsState(): StateFlow<ContactsState> = repository.contacts

    fun isSignedIn(): Boolean = container.credentialStore.hasCredentials()

    fun signedInUsername(): String? = container.credentialStore.username()

    fun signedInDisplayName(): String? = repository.account.value.me?.displayName

    fun presenceOf(username: String): PresenceStatus {
        val contacts = repository.contacts.value.contacts
        return when (contacts.firstOrNull { it.username == username }?.presence) {
            "online" -> PresenceStatus.ONLINE
            "offline" -> PresenceStatus.OFFLINE
            else -> PresenceStatus.UNKNOWN
        }
    }

    fun displayNameOf(username: String, contacts: List<ContactDto>): String =
        contacts.firstOrNull { it.username == username }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: username

    fun messagesFor(peer: String): StateFlow<List<ChatMessage>> = repository.chatFlow(peer)

    fun chatDraftFor(peer: String): StateFlow<String> = repository.chatDraftFlow(peer)

    fun onChatInputChanged(peer: String, value: String) {
        chatInput = value
        repository.saveChatDraft(peer, value)
    }

    fun sendCurrent(peer: String) {
        val content = chatInput.trim()
        if (content.isEmpty()) return
        chatInput = ""
        repository.saveChatDraft(peer, "")
        repository.send(peer, content) { }
    }

    fun openChat(peer: String) {
        _screen.value = AppScreen.Chat(peer)
        repository.account.value.me?.let { me -> repository.loadHistory(peer, me.username) }
    }

    fun backToContacts() {
        _screen.value = AppScreen.Contacts
    }

    fun openSettings() {
        _screen.value = AppScreen.Settings
    }

    fun refreshContactsSilently() {
        repository.refreshContacts()
    }

    fun startCall(peer: String) {
        engine.call(remoteUser = peer)
        _screen.value = AppScreen.Call(peer, incoming = false)
    }

    fun acceptCall() = engine.accept()

    fun rejectCall() = engine.reject()

    fun hangUp() = engine.hangUp()

    fun toggleMute() {
        engine.setMute(!_callState.value.muted)
    }

    fun toggleSpeaker() {
        engine.setSpeaker(!_callState.value.speakerOn)
    }

    fun logout() {
        engine.stop()
        repository.clearSession()
        container.credentialStore.clear()
        serverUrlInput = ""
        usernameInput = ""
        passwordInput = ""
        chatInput = ""
        _screen.value = AppScreen.Onboarding
    }

    fun clearLocalData() {
        repository.clearSession()
    }

    private fun currentStartScreen(): AppScreen =
        if (container.credentialStore.hasCredentials()) AppScreen.Contacts else AppScreen.Onboarding

    companion object {
        fun factory(context: android.content.Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as com.patbaumgartner.android.stringtelephone.StringTelephoneApp
                return AppViewModel(app.container) as T
            }
        }
    }
}
