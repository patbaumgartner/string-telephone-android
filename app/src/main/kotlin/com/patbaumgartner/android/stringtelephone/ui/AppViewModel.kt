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
import com.patbaumgartner.android.stringtelephone.call.CallFailure
import com.patbaumgartner.android.stringtelephone.call.CallForegroundService
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ServerUrlValidation
import com.patbaumgartner.android.stringtelephone.core.validateServerUrl
import com.patbaumgartner.android.stringtelephone.data.AccountState
import com.patbaumgartner.android.stringtelephone.data.ContactsState
import com.patbaumgartner.android.stringtelephone.data.SignInState
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.notify.IncomingCallAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val session = container.session

    var serverUrlInput by mutableStateOf(BuildConfig.DEV_SERVER_URL)
        private set
    var usernameInput by mutableStateOf(BuildConfig.DEV_USERNAME)
        private set
    var passwordInput by mutableStateOf(BuildConfig.DEV_PASSWORD)
        private set
    var sipSecretInput by mutableStateOf(BuildConfig.DEV_SIP_SECRET)
        private set
    var chatInput by mutableStateOf("")
        private set
    var callRegistrationFailed by mutableStateOf(false)
        private set

    /** Whether Android lets a ringing call open the app; refreshed whenever the UI resumes. */
    var incomingCallAccess by mutableStateOf(IncomingCallAccess.READY)
        private set

    fun updateIncomingCallAccess(access: IncomingCallAccess) {
        incomingCallAccess = access
    }

    /** Set when the user tapped Answer in the notification; the UI runs the microphone gate. */
    var pendingAnswer by mutableStateOf(false)
        private set

    /** Why the last outgoing call failed; the call screen explains it before returning. */
    var callFailure by mutableStateOf<CallFailure?>(null)
        private set
    private var failureDismissJob: Job? = null

    private val _screen = MutableStateFlow(currentStartScreen())
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionInfo(remoteUser = "", state = CallState.IDLE))
    val callState: StateFlow<CallSessionInfo> = _callState.asStateFlow()

    init {
        session.start()
        viewModelScope.launch {
            engine.events.collect { event -> onCallEvent(event) }
        }
        viewModelScope.launch {
            engine.state.collect { info -> _callState.value = info }
        }
        viewModelScope.launch {
            session.registrationFailed.collect { callRegistrationFailed = it }
        }
        viewModelScope.launch {
            _screen.collect { screen -> session.onChatShown((screen as? AppScreen.Chat)?.peer) }
        }
        viewModelScope.launch {
            repository.signIn.collect { state ->
                if (state.success) {
                    if (_screen.value == AppScreen.Onboarding) _screen.value = currentStartScreen()
                } else if (state.error != null) {
                    _screen.value = AppScreen.Onboarding
                }
            }
        }
        if (isSignedIn() && repository.account.value.me == null) {
            serverUrlInput = container.credentialStore.serverUrl().orEmpty()
            usernameInput = container.credentialStore.username().orEmpty()
            passwordInput = container.credentialStore.apiToken().orEmpty()
            sipSecretInput = container.credentialStore.sipSecret().orEmpty()
        }
        session.resume()
    }

    /** Navigation only; notifications, missed calls and the call service live in the session. */
    private fun onCallEvent(event: CallEvent) {
        when (event) {
            is CallEvent.IncomingCall -> {
                clearCallFailure()
                _screen.value = AppScreen.Call(event.remoteUser, incoming = true)
            }
            is CallEvent.CallFailed -> {
                pendingAnswer = false
                val screen = _screen.value
                if (screen is AppScreen.Call && !screen.incoming) {
                    showCallFailure(event.failure)
                } else if (screen is AppScreen.Call) {
                    _screen.value = AppScreen.Contacts
                }
            }
            is CallEvent.CallEnded -> {
                pendingAnswer = false
                clearCallFailure()
                if (_screen.value is AppScreen.Call) _screen.value = AppScreen.Contacts
            }
            else -> Unit
        }
    }

    private fun showCallFailure(failure: CallFailure) {
        callFailure = failure
        failureDismissJob?.cancel()
        failureDismissJob = viewModelScope.launch {
            delay(CALL_FAILURE_VISIBLE_MS)
            dismissCallFailure()
        }
    }

    private fun clearCallFailure() {
        failureDismissJob?.cancel()
        failureDismissJob = null
        callFailure = null
    }

    /** Leaves the failed call screen now instead of waiting for it to close itself. */
    fun dismissCallFailure() {
        val wasShowing = callFailure != null
        clearCallFailure()
        if (wasShowing && _screen.value is AppScreen.Call) _screen.value = AppScreen.Contacts
    }

    /** Applies a notification tap delivered to [MainActivity]. */
    fun handleLaunchAction(action: String?, peer: String?) {
        if (!repository.signIn.value.success) return
        when (action) {
            MainActivity.ACTION_SHOW_CALL, MainActivity.ACTION_ANSWER_CALL -> {
                val call = engine.state.value
                if (call.state in ONGOING_CALL_STATES) {
                    val ringing = call.state == CallState.RINGING
                    _screen.value = AppScreen.Call(call.remoteUser, incoming = ringing)
                    if (ringing && action == MainActivity.ACTION_ANSWER_CALL) pendingAnswer = true
                }
            }
            MainActivity.ACTION_OPEN_CHAT -> if (!peer.isNullOrBlank()) openChat(peer)
            MainActivity.ACTION_OPEN_CONTACTS -> if (_screen.value !is AppScreen.Call) {
                _screen.value = AppScreen.Contacts
            }
        }
    }

    fun consumePendingAnswer() {
        pendingAnswer = false
    }

    override fun onCleared() {
        session.onChatShown(null)
        super.onCleared()
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

    fun onSipSecretChanged(value: String) {
        sipSecretInput = value
    }

    fun onChatInputChanged(value: String) {
        chatInput = value
    }

    fun signIn() {
        if (repository.signIn.value.inProgress) return
        val urlValidation = validateServerUrl(serverUrlInput)
        if (urlValidation !is ServerUrlValidation.Valid) {
            repository.reportSignInError(SignInError.INVALID_URL)
            return
        }
        if (!urlValidation.secure && !BuildConfig.DEBUG) {
            repository.reportSignInError(SignInError.INSECURE_URL)
            return
        }
        if (usernameInput.isBlank() || passwordInput.isBlank() || sipSecretInput.isBlank()) {
            repository.reportSignInError(SignInError.MISSING_CREDENTIALS)
            return
        }
        container.credentialStore.save(
            serverUrl = urlValidation.normalized,
            username = usernameInput.trim(),
            apiToken = passwordInput,
            sipSecret = sipSecretInput,
        )
        session.signInWithNewCredentials(expectedUsername = usernameInput.trim())
    }

    /** True when the address currently typed is a cleartext http:// URL. */
    fun serverUrlIsInsecure(): Boolean =
        (validateServerUrl(serverUrlInput) as? ServerUrlValidation.Valid)?.secure == false

    fun signInState(): StateFlow<SignInState> = repository.signIn

    fun accountState(): StateFlow<AccountState> = repository.account

    fun contactsState(): StateFlow<ContactsState> = repository.contacts

    fun isSignedIn(): Boolean = container.credentialStore.hasCredentials()

    fun signedInUsername(): String? = container.credentialStore.username()

    fun signedInDisplayName(): String? = repository.account.value.me?.displayName

    fun unreadState(): StateFlow<Map<String, Int>> = repository.unread

    fun buildInfo(): com.patbaumgartner.android.stringtelephone.di.BuildInfo = container.buildInfo

    fun displayNameOf(username: String, contacts: List<ContactDto>): String =
        contacts.firstOrNull { it.username == username }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: username

    fun messagesFor(peer: String): StateFlow<List<ChatMessage>> = repository.chatFlow(peer)

    fun historyErrorFor(peer: String): StateFlow<Boolean> = repository.historyErrorFlow(peer)

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

    /** Tapping a message that was not delivered sends it again. */
    fun retrySend(peer: String, message: ChatMessage) {
        repository.resend(peer, message)
    }

    fun openChat(peer: String) {
        chatInput = repository.chatDraftFlow(peer).value
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

    fun retryCallRegistration() {
        callRegistrationFailed = false
        session.restartEngine()
    }

    /** Callers run the microphone permission gate first; the call service needs a visible app. */
    fun startCall(peer: String) {
        clearCallFailure()
        // Navigate first: a call that fails synchronously must find the call screen to explain itself.
        _screen.value = AppScreen.Call(peer, incoming = false)
        engine.call(remoteUser = peer)
        if (callFailure == null && engine.state.value.state == CallState.OUTGOING) startCallService(peer)
    }

    fun acceptCall() {
        pendingAnswer = false
        engine.accept()
        startCallService(_callState.value.remoteUser)
    }

    fun rejectCall() {
        session.onCallDeclined()
        engine.reject()
    }

    fun hangUp() {
        if (callFailure != null) {
            dismissCallFailure()
            return
        }
        if (_callState.value.state == CallState.RINGING) session.onCallDeclined()
        engine.hangUp()
    }

    private fun startCallService(peer: String) {
        runCatching { CallForegroundService.start(container.context, peer) }
    }

    fun toggleMute() {
        engine.setMute(!_callState.value.muted)
    }

    fun toggleSpeaker() {
        engine.setSpeaker(!_callState.value.speakerOn)
    }

    fun logout() {
        session.signOut()
        serverUrlInput = ""
        usernameInput = ""
        passwordInput = ""
        sipSecretInput = ""
        chatInput = ""
        callRegistrationFailed = false
        pendingAnswer = false
        _screen.value = AppScreen.Onboarding
    }

    fun clearLocalData() {
        logout()
    }

    /** A UI opened during a call (e.g. from the incoming-call notification) lands on the call. */
    private fun currentStartScreen(): AppScreen {
        if (!repository.signIn.value.success) return AppScreen.Onboarding
        val call = engine.state.value
        return if (call.state in ONGOING_CALL_STATES) {
            AppScreen.Call(call.remoteUser, incoming = call.state == CallState.RINGING)
        } else {
            AppScreen.Contacts
        }
    }

    companion object {
        private val ONGOING_CALL_STATES = setOf(CallState.RINGING, CallState.OUTGOING, CallState.ACTIVE)
        internal const val CALL_FAILURE_VISIBLE_MS = 4_000L

        fun factory(context: android.content.Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as com.patbaumgartner.android.stringtelephone.StringTelephoneApp
                return AppViewModel(app.container) as T
            }
        }
    }
}
