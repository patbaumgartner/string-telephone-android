package com.patbaumgartner.android.stringtelephone.ui

import android.content.Context
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.call.EngineConfig
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.security.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Call engine that never touches liblinphone; drives deterministic UI state. */
class FakeCallEngine(initial: CallSessionInfo = CallSessionInfo("", CallState.IDLE)) : CallEngine {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<CallSessionInfo> = _state
    override val events = kotlinx.coroutines.flow.MutableSharedFlow<CallEvent>()
    var startedConfig: EngineConfig? = null
        private set

    override fun start(config: EngineConfig) { startedConfig = config }
    override fun stop() { startedConfig = null }
    override fun call(remoteUser: String) {}
    override fun accept() {}
    override fun reject() {}
    override fun hangUp() {}
    override fun setMute(muted: Boolean) {}
    override fun setSpeaker(on: Boolean) {}
}

/** In-memory credential store for previews and screenshot tests. */
class FakeCredentialStore : CredentialStore {
    private var url: String? = null
    private var user: String? = null
    private var token: String? = null
    private var sip: String? = null

    override fun save(serverUrl: String, username: String, apiToken: String, sipSecret: String) {
        url = serverUrl
        user = username
        token = apiToken
        sip = sipSecret
    }

    override fun serverUrl(): String? = url
    override fun username(): String? = user
    override fun apiToken(): String? = token
    override fun sipSecret(): String? = sip
    override fun hasCredentials(): Boolean = url != null && token != null
    override fun clear() {
        url = null; user = null; token = null; sip = null
    }
}


/**
 * Builds an AppViewModel whose repository is backed by fakes and seeded with
 * preview data, so screenshot tests render populated screens.
 */
object PreviewFactory {
    fun viewModel(context: Context, callEngine: CallEngine = FakeCallEngine()): AppViewModel {
        val cache = ChatCache()
        cache.append("bob", com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
            "preview-1", "preview-1", false, "Hello Alice! Are you free for a call?", 1_000,
            com.patbaumgartner.android.stringtelephone.chat.SendState.SENT,
        ))
        cache.append("bob", com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
            "preview-2", "preview-2", true, "Hi Bob, yes. Give me a moment.", 2_000,
            com.patbaumgartner.android.stringtelephone.chat.SendState.SENT,
        ))
        val store = FakeCredentialStore()
        store.save(
            serverUrl = "https://phone.example.com",
            username = "alice",
            apiToken = "st_preview_token",
            sipSecret = "preview-sip-secret",
        )
        val container = AppContainer(
            context,
            credentialStoreOverride = store,
            callEngineOverride = callEngine,
            repositoryOverride = StringTelephoneRepository({ null }, cache),
        )
        container.repository.seedForPreviews(
            me = com.patbaumgartner.android.stringtelephone.network.MeResult("alice", "Alice"),
            contacts = listOf(
                com.patbaumgartner.android.stringtelephone.core.ContactDto("bob", "Bob", "online"),
                com.patbaumgartner.android.stringtelephone.core.ContactDto("carol", "Carol", "offline"),
                com.patbaumgartner.android.stringtelephone.core.ContactDto("dave", "Dave", "online"),
            ),
        )
        val viewModel = AppViewModel(container)
        return viewModel
    }
}
