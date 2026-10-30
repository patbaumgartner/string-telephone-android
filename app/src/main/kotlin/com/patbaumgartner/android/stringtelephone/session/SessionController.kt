package com.patbaumgartner.android.stringtelephone.session

import android.content.Context
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallForegroundService
import com.patbaumgartner.android.stringtelephone.call.EngineConfig
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.core.deriveSipTarget
import com.patbaumgartner.android.stringtelephone.data.IncomingMessage
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.data.SignInState
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.notify.AppNotifier
import com.patbaumgartner.android.stringtelephone.security.CredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped owner of the signed-in session. It lives as long as the process,
 * not the Activity, so SIP registration, the realtime socket, incoming-call
 * and message notifications keep working after the UI is closed (ADR-0006).
 * The ViewModel only navigates; every background side effect lives here.
 * All methods must be called on the main thread.
 */
class SessionController(
    private val context: Context,
    private val credentialStore: CredentialStore,
    private val repository: StringTelephoneRepository,
    private val engine: CallEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var appVisible = false
    private var visibleChat: String? = null
    private var awaitingIncomingPeer: String? = null
    private var engineConfig: EngineConfig? = null
    private var credentialsPendingVerification = false
    private var retryJob: Job? = null
    private var retryAttempts = 0
    private val recentMessages = mutableMapOf<String, ArrayDeque<IncomingMessage>>()

    private val _registrationFailed = MutableStateFlow(false)

    /** True after SIP registration failed while the API session is fine. */
    val registrationFailed: StateFlow<Boolean> = _registrationFailed.asStateFlow()

    /** Starts observing engine, sign-in and message events; safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        scope.launch { engine.events.collect { onCallEvent(it) } }
        scope.launch { repository.signIn.collect { onSignInState(it) } }
        scope.launch { repository.incomingMessages.collect { onIncomingMessage(it) } }
        scope.launch { repository.readElsewhere.collect { onReadElsewhere(it) } }
    }

    /** Verifies freshly entered credentials; a rejection clears them. */
    fun signInWithNewCredentials(expectedUsername: String) {
        cancelRetry()
        credentialsPendingVerification = true
        repository.signInAndLoad(expectedUsername = expectedUsername)
    }

    /**
     * Restores the stored session if it is not already signed in or signing
     * in. Called when the UI opens and when the keep-alive service restarts.
     */
    fun resume() {
        if (!credentialStore.hasCredentials()) return
        val state = repository.signIn.value
        when {
            state.success -> startEngine(force = false)
            !state.inProgress -> repository.signInAndLoad(expectedUsername = credentialStore.username())
        }
    }

    fun restartEngine() {
        if (repository.signIn.value.success) startEngine(force = true)
    }

    fun signOut() {
        cancelRetry()
        stopEngine()
        KeepAliveService.stop(context)
        CallForegroundService.stop(context)
        repository.clearSession()
        credentialStore.clear()
        credentialsPendingVerification = false
        awaitingIncomingPeer = null
        recentMessages.clear()
        _registrationFailed.value = false
        AppNotifier.cancelAll(context)
    }

    fun onAppVisible() {
        appVisible = true
        updateActiveConversation()
        AppNotifier.cancelIncomingCall(context)
        if (repository.signIn.value.success) {
            KeepAliveService.start(context)
            repository.refreshContacts()
        }
        resume()
    }

    fun onAppHidden() {
        appVisible = false
        updateActiveConversation()
        val call = engine.state.value
        if (call.state == CallState.RINGING) showIncomingCall(call.remoteUser, call.videoOffered)
    }

    /** The conversation shown by the UI, or null when no chat is open. */
    fun onChatShown(peer: String?) {
        visibleChat = peer
        updateActiveConversation()
    }

    /** The user declined deliberately, so the ended call is not "missed". */
    fun onCallDeclined() {
        awaitingIncomingPeer = null
        AppNotifier.cancelIncomingCall(context)
    }

    private fun onSignInState(state: SignInState) {
        if (state.success) {
            credentialsPendingVerification = false
            cancelRetry()
            startEngine(force = false)
            KeepAliveService.start(context)
            return
        }
        val error = state.error ?: return
        if (!credentialStore.hasCredentials()) return
        stopEngine()
        val rejected = credentialsPendingVerification ||
            error == SignInError.AUTHENTICATION || error == SignInError.IDENTITY
        credentialsPendingVerification = false
        when {
            rejected -> {
                credentialStore.clear()
                KeepAliveService.stop(context)
            }
            error == SignInError.NETWORK || error == SignInError.SERVER -> scheduleRetry()
        }
    }

    private fun onCallEvent(event: CallEvent) {
        when (event) {
            CallEvent.Registered -> _registrationFailed.value = false
            CallEvent.RegistrationFailed -> _registrationFailed.value = true
            is CallEvent.IncomingCall -> {
                awaitingIncomingPeer = event.remoteUser
                if (!appVisible) showIncomingCall(event.remoteUser, event.withVideo)
            }
            is CallEvent.CallConnected -> {
                awaitingIncomingPeer = null
                AppNotifier.cancelIncomingCall(context)
                runCatching { CallForegroundService.start(context, event.remoteUser) }
            }
            is CallEvent.CallEnded, is CallEvent.CallFailed -> {
                AppNotifier.cancelIncomingCall(context)
                awaitingIncomingPeer?.let { peer ->
                    AppNotifier.notifyMissedCall(context, peer, displayNameOf(peer))
                }
                awaitingIncomingPeer = null
                CallForegroundService.stop(context)
            }
            is CallEvent.OutgoingRinging -> Unit
        }
    }

    private fun onIncomingMessage(message: IncomingMessage) {
        if (repository.isActiveConversation(message.peer)) return
        // Muted conversations (Phase 18) keep their unread badge but raise
        // no notification, mirroring WhatsApp's mute semantics.
        if (repository.isMuted(message.peer)) return
        val lines = recentMessages.getOrPut(message.peer) { ArrayDeque() }
        lines.addLast(message)
        while (lines.size > MAX_NOTIFICATION_LINES) lines.removeFirst()
        AppNotifier.notifyConversation(
            context = context,
            peer = message.peer,
            displayPeer = displayNameOf(message.peer),
            messages = lines.map { it.content to it.createdAtEpochMs },
            unreadCount = repository.unread.value[message.peer] ?: lines.size,
        )
    }

    private fun updateActiveConversation() {
        val active = visibleChat.takeIf { appVisible }
        repository.setActiveConversation(active)
        if (active != null) {
            recentMessages.remove(active)
            AppNotifier.cancelConversation(context, active)
        }
    }

    /**
     * Another device of this account marked the conversation read (Phase
     * 13): drop this device's notification and buffered lines so the shade
     * matches what the user has already seen elsewhere.
     */
    private fun onReadElsewhere(peer: String) {
        if (repository.isActiveConversation(peer)) return
        recentMessages.remove(peer)
        AppNotifier.cancelConversation(context, peer)
    }

    private fun showIncomingCall(peer: String, withVideo: Boolean = false) {
        AppNotifier.notifyIncomingCall(context, peer, displayNameOf(peer), withVideo)
    }

    /**
     * Starts SIP registration against the registrar implied by the stored base
     * URL: an https server registers over TLS on 5061, an http server over UDP
     * on 5060. Provisioning supplies separate API and SIP credentials.
     */
    private fun startEngine(force: Boolean) {
        val serverUrl = credentialStore.serverUrl() ?: return
        val username = credentialStore.username() ?: return
        val secret = credentialStore.sipSecret() ?: return
        val target = deriveSipTarget(serverUrl) ?: return
        val config = EngineConfig(
            serverHost = target.host,
            serverPort = target.port,
            username = username,
            sipSecret = secret,
            transport = if (target.secure) EngineConfig.Transport.TLS else EngineConfig.Transport.UDP,
        )
        if (!force && config == engineConfig) return
        stopEngine()
        _registrationFailed.value = false
        try {
            engine.start(config)
            engineConfig = config
        } catch (_: IllegalArgumentException) {
            _registrationFailed.value = true
        } catch (_: IllegalStateException) {
            _registrationFailed.value = true
        }
    }

    private fun stopEngine() {
        engine.stop()
        engineConfig = null
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        val delayMs = RETRY_BASE_MS shl retryAttempts.coerceAtMost(RETRY_MAX_SHIFT)
        retryAttempts++
        retryJob = scope.launch {
            delay(delayMs)
            resume()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryAttempts = 0
    }

    private fun displayNameOf(username: String): String =
        repository.contacts.value.contacts.firstOrNull { it.username == username }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: username

    private companion object {
        const val MAX_NOTIFICATION_LINES = 6
        const val RETRY_BASE_MS = 15_000L
        const val RETRY_MAX_SHIFT = 4
    }
}
