package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.patbaumgartner.android.stringtelephone.BuildConfig
import com.patbaumgartner.android.stringtelephone.call.AudioRoute
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallFailure
import com.patbaumgartner.android.stringtelephone.call.CallForegroundService
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.ImageDownscale
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ServerUrlValidation
import com.patbaumgartner.android.stringtelephone.core.validateServerUrl
import com.patbaumgartner.android.stringtelephone.data.AccountState
import com.patbaumgartner.android.stringtelephone.data.ContactsState
import com.patbaumgartner.android.stringtelephone.data.SignInState
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.notify.IncomingCallAccess
import com.patbaumgartner.android.stringtelephone.voice.MediaPlayerVoicePlayer
import com.patbaumgartner.android.stringtelephone.voice.MediaRecorderVoiceRecorder
import com.patbaumgartner.android.stringtelephone.voice.PlaybackSpeed
import com.patbaumgartner.android.stringtelephone.voice.VoicePlayer
import com.patbaumgartner.android.stringtelephone.voice.VoicePlayerState
import com.patbaumgartner.android.stringtelephone.voice.VoiceRecording
import com.patbaumgartner.android.stringtelephone.voice.VoiceRecordingState
import com.patbaumgartner.android.stringtelephone.voice.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    /**
     * The message the user is replying to (ADR-0011, Phase 9); shown as a
     * banner above the composer and cleared when the reply is sent or dismissed.
     */
    private val _replyingTo = MutableStateFlow<ChatMessage?>(null)
    val replyingTo: StateFlow<ChatMessage?> = _replyingTo.asStateFlow()

    /** Why the last outgoing call failed; the call screen explains it before returning. */
    var callFailure by mutableStateOf<CallFailure?>(null)
        private set
    private var failureDismissJob: Job? = null
    private var typingDebounceJob: Job? = null

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

    /** Per-peer newest message for the conversation-list previews (Phase 15). */
    fun previewsState(): StateFlow<Map<String, ChatMessage>> = repository.previews

    /** Local contact filtering for the conversation list search (Phase 15). */
    fun filterContacts(contacts: List<ContactDto>, query: String): List<ContactDto> =
        repository.filterContacts(contacts, query)

    fun isSignedIn(): Boolean = container.credentialStore.hasCredentials()

    fun signedInUsername(): String? = container.credentialStore.username()

    fun signedInDisplayName(): String? = repository.account.value.me?.displayName

    /** The signed-in username; reaction toggles need it to spot "my" chips. */
    fun meUsername(): String? = repository.account.value.me?.username

    /** Toggles the signed-in user's reaction on a message (ADR-0011, Phase 8). */
    fun toggleReaction(peer: String, message: ChatMessage, emoji: String) {
        repository.toggleReaction(peer, message, emoji)
    }

    /** Sets the message being replied to; shown as a quoted banner (ADR-0011, Phase 9). */
    fun setReplyingTo(message: ChatMessage?) {
        _replyingTo.value = message
    }

    /** Clears the current reply target (ADR-0011, Phase 9). */
    fun clearReplyTo() {
        _replyingTo.value = null
    }

    /** Deletes a message for both sides (ADR-0011, Phase 10). */
    fun deleteMessage(peer: String, message: ChatMessage) {
        repository.deleteMessage(peer, message)
        // A pending reply to a deleted message makes no sense; drop the banner.
        if (_replyingTo.value?.clientKey == message.clientKey) _replyingTo.value = null
    }

    /** Hides a message on this device only (ADR-0011, Phase 10). */
    fun deleteForMe(peer: String, message: ChatMessage) {
        repository.deleteForMe(peer, message)
        if (_replyingTo.value?.clientKey == message.clientKey) _replyingTo.value = null
    }

    fun unreadState(): StateFlow<Map<String, Int>> = repository.unread

    /** The username of the peer currently composing, or null when the indicator is hidden. */
    fun typingPeerState(): StateFlow<String?> = repository.typingPeer

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
        // Debounce: fire only after 500 ms of no typing.
        typingDebounceJob?.cancel()
        typingDebounceJob = viewModelScope.launch {
            delay(TYPING_DEBOUNCE_MS)
            typingDebounceJob = null
            repository.notifyTyping()
        }
    }

    fun attachImage(peer: String, uri: android.net.Uri) {
        val context = container.context
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    container.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                val scaled = withContext(Dispatchers.IO) {
                    ImageDownscale.downscale(bytes)
                }
                sendImage(peer, scaled, "image/jpeg", "image.jpg")
            } catch (_: Exception) {
            }
        }
    }

    // ── Voice messages (Phase 11) ────────────────────────────────────────────

    private val voiceRecorder: VoiceRecorder by lazy {
        val dir = java.io.File(container.context.cacheDir, "voice")
        dir.mkdirs()
        MediaRecorderVoiceRecorder(container.context, dir, viewModelScope)
    }

    private val voicePlayer: VoicePlayer by lazy {
        MediaPlayerVoicePlayer(container.context, viewModelScope)
    }

    val voiceRecordingState: StateFlow<VoiceRecordingState>
        get() = voiceRecorder.state

    val voicePlayerState: StateFlow<VoicePlayerState>
        get() = voicePlayer.state

    val voicePlaybackSpeed: StateFlow<PlaybackSpeed>
        get() = voicePlayer.currentSpeed

    /** Starts recording; the composer draws [voiceRecordingState] while a take runs. */
    fun startVoiceRecording(): Boolean = voiceRecorder.start()

    /**
     * Finishes the held recording and sends it as a VOICE message. The reply
     * banner (if any) quotes the finished message. A delivery failure leaves
     * a failed bubble the user can retry; an upload failure discards the
     * recording, the same way a failed image upload is discarded.
     */
    fun finishVoiceRecording(peer: String) {
        val replyToId = _replyingTo.value?.serverId
        _replyingTo.value = null
        voiceRecorder.finish { recording ->
            if (recording == null) return@finish
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    repository.sendVoiceRecording(
                        peer,
                        recording.bytes,
                        recording.contentType,
                        recording.fileName,
                        recording.durationMs,
                        recording.waveform,
                        replyToId,
                    ) { }
                }
            }
        }
    }

    /** Aborts the in-progress recording (cancel gesture); safe when idle. */
    fun cancelVoiceRecording() {
        voiceRecorder.cancel()
    }

    // ── Voice playback (Phase 11) ────────────────────────────────────────────

    /** Plays the message's media through the authenticated media endpoint. */
    fun playVoiceMessage(message: ChatMessage) {
        val mediaId = message.mediaId ?: return
        val baseUrl = repository.apiBaseUrl() ?: return
        val token = repository.apiToken()
        voicePlayer.play(mediaId, "$baseUrl/v1/media/$mediaId", token)
    }

    fun pauseVoicePlayback() = voicePlayer.pause()
    fun resumeVoicePlayback() = voicePlayer.resume()
    fun seekVoicePlayback(positionMs: Int) = voicePlayer.seekTo(positionMs)
    fun cycleVoicePlaybackSpeed() = voicePlayer.cycleSpeed()
    fun stopVoicePlayback() = voicePlayer.stop()

    fun sendCurrent(peer: String) {
        val content = chatInput.trim()
        if (content.isEmpty()) return
        chatInput = ""
        val replyToId = _replyingTo.value?.serverId
        _replyingTo.value = null
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        repository.saveChatDraft(peer, "")
        repository.sendTyping(false)
        repository.send(peer, content, replyToId) { }
    }

    fun sendImage(peer: String, bytes: ByteArray, contentType: String, fileName: String) {
        viewModelScope.launch {
            try {
                val media = withContext(Dispatchers.IO) { repository.uploadMedia(peer, bytes, contentType, fileName) }
                repository.sendImage(peer, media.id, media.width, media.height) { }
            } catch (_: Exception) {
                // Failed uploads surface as failed text-less bubbles is handled by sendImage's deliver path.
            }
        }
    }

    /** Tapping a message that was not delivered sends it again. */
    fun retrySend(peer: String, message: ChatMessage) {
        repository.resend(peer, message)
    }

    fun openChat(peer: String) {
        chatInput = repository.chatDraftFlow(peer).value
        _replyingTo.value = null
        _screen.value = AppScreen.Chat(peer)
        repository.account.value.me?.let { me -> repository.loadHistory(peer, me.username) }
    }

    /**
     * Marks the peer's messages as read (ADR-0010).
     *
     * Only the chat screen calls this, and only while the messages are on
     * screen. A notification, a background sync or a cursor catch-up must
     * never mark a message read.
     */
    fun mediaBaseUrl(): String? = repository.apiBaseUrl()
    fun mediaToken(): String? = repository.apiToken()

    fun markConversationRead(peer: String, upToMessageId: String?) {
        repository.markConversationRead(peer, upToMessageId)
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
    fun startCall(peer: String, withVideo: Boolean = false) {
        clearCallFailure()
        // Navigate first: a call that fails synchronously must find the call screen to explain itself.
        _screen.value = AppScreen.Call(peer, incoming = false)
        engine.call(remoteUser = peer, withVideo = withVideo)
        if (callFailure == null && engine.state.value.state == CallState.OUTGOING) startCallService(peer)
    }

    fun acceptCall(withVideo: Boolean = false) {
        pendingAnswer = false
        engine.accept(withVideo = withVideo)
        startCallService(_callState.value.remoteUser)
    }

    /** Camera on/off mid-call; the engine keeps the peer visible when the camera stops. */
    fun setVideoEnabled(enabled: Boolean) {
        engine.setVideoEnabled(enabled)
    }

    fun switchCamera() {
        engine.switchCamera()
    }

    /**
     * The app left the foreground: drop video, keep audio. Called from
     * MainActivity.onStop because a backgrounded app gets no camera frames.
     */
    fun onCallScreenHidden() {
        engine.releaseVideo()
    }

    /** The call screen hands its native surfaces to the engine and clears them on dispose. */
    fun attachVideoSurfaces(remote: Any?, preview: Any?) {
        engine.attachVideoSurfaces(remote, preview)
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

    fun setAudioRoute(route: AudioRoute) {
        engine.setAudioRoute(route)
    }

    fun logout() {
        session.signOut()
        serverUrlInput = ""
        usernameInput = ""
        passwordInput = ""
        sipSecretInput = ""
        chatInput = ""
        _replyingTo.value = null
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
        internal const val TYPING_DEBOUNCE_MS = 500L

        fun factory(context: android.content.Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as com.patbaumgartner.android.stringtelephone.StringTelephoneApp
                return AppViewModel(app.container) as T
            }
        }
    }
}
