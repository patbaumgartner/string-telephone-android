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
    var rejected = false
        private set

    fun setState(info: CallSessionInfo) { _state.value = info }

    override fun start(config: EngineConfig) { startedConfig = config }
    override fun stop() { startedConfig = null }
    val calls = mutableListOf<String>()
    var hungUp = false
        private set

    override fun call(remoteUser: String) {
        calls += remoteUser
        _state.value = CallSessionInfo(remoteUser, CallState.OUTGOING)
    }
    override fun accept() {}
    override fun reject() { rejected = true }
    override fun hangUp() { hungUp = true }
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
/** Fixed past timestamps keep the rendered day chips and times stable (screenshots run in UTC). */
private val PREVIEW_CHAT = listOf(
    Triple(false, "Hello Alice! Are you free for a call later?", "2025-06-12T18:02:00Z"),
    Triple(true, "Hi Bob, yes. After dinner works for me.", "2025-06-12T18:05:00Z"),
    Triple(false, "Great \uD83D\uDC4D", "2025-06-13T08:41:00Z"),
    Triple(false, "I tried earlier but you were offline. Ring me whenever you are back home.", "2025-06-13T08:42:00Z"),
    Triple(true, "On my way now", "2025-06-13T09:10:00Z"),
    Triple(true, "Call you in ten minutes!", "2025-06-13T09:11:00Z"),
)

object PreviewFactory {
    fun viewModel(context: Context, callEngine: CallEngine = FakeCallEngine()): AppViewModel {
        val cache = ChatCache()
        PREVIEW_CHAT.forEachIndexed { index, (fromMe, content, at) ->
            val state = when (index) {
                PREVIEW_CHAT.lastIndex -> com.patbaumgartner.android.stringtelephone.chat.SendState.QUEUED
                PREVIEW_CHAT.lastIndex - 1 -> com.patbaumgartner.android.stringtelephone.chat.SendState.FAILED
                else -> com.patbaumgartner.android.stringtelephone.chat.SendState.SENT
            }
            val key = "preview-" + index
            cache.append(
                "bob",
                com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
                    key, key, fromMe, content, java.time.Instant.parse(at).toEpochMilli(), state,
                ),
            )
        }
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
            buildInfo = com.patbaumgartner.android.stringtelephone.di.BuildInfo("0.1.0", "1a2b3c4d5e"),
        )
        container.repository.seedForPreviews(
            me = com.patbaumgartner.android.stringtelephone.network.MeResult("alice", "Alice"),
            contacts = listOf(
                com.patbaumgartner.android.stringtelephone.core.ContactDto("bob", "Bob", "online"),
                com.patbaumgartner.android.stringtelephone.core.ContactDto("carol", "Carol", "offline"),
                com.patbaumgartner.android.stringtelephone.core.ContactDto("dave", "Dave", "online"),
            ),
            unread = mapOf("carol" to 2),
        )
        val viewModel = AppViewModel(container)
        return viewModel
    }
}
