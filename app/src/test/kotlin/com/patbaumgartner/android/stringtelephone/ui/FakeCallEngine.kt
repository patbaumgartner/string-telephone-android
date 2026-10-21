package com.patbaumgartner.android.stringtelephone.ui

import android.content.Context
import com.patbaumgartner.android.stringtelephone.call.AudioRoute
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
    val routed = mutableListOf<AudioRoute>()
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
    override fun setAudioRoute(route: AudioRoute) {
        routed += route
        _state.value = _state.value.copy(audioRoute = route)
    }
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
// Long enough (well over SCROLLBAR_MIN_ITEMS rows) that the chat screenshot
// exercises the scroll thumb; the failed/queued states stay on the last two
// entries so the existing renders keep their status fixtures.
private val PREVIEW_CHAT = buildList {
    add(Triple(false, "Hello Alice! Are you free for a call later?", "2025-06-12T18:02:00Z"))
    add(Triple(true, "Hi Bob, yes. After dinner works for me.", "2025-06-12T18:05:00Z"))
    repeat(12) { round ->
        add(
            Triple(
                false,
                "Round ${round + 1}: anything new on your side?",
                "2025-06-13T08:${(10 + round).toString().padStart(2, '0')}:00Z",
            ),
        )
        add(
            Triple(
                true,
                "Round ${round + 1}: nothing new here. Still on for the weekend?" +
                    if (round == 6) {
                        " This one runs long enough to wrap over several lines, keeping row heights varied."
                    } else {
                        ""
                    },
                "2025-06-13T08:${(20 + round).toString().padStart(2, '0')}:00Z",
            ),
        )
    }
    add(Triple(false, "Great 👍", "2025-06-13T09:40:00Z"))
    add(Triple(false, "I tried earlier but you were offline. Ring me whenever you are back home.", "2025-06-13T09:41:00Z"))
    add(Triple(true, "On my way now", "2025-06-13T10:10:00Z"))
    add(Triple(true, "Call you in ten minutes!", "2025-06-13T10:11:00Z"))
}

object PreviewFactory {
    /**
     * One outgoing message per delivery state (ADR-0010), so the screenshot
     * shows every tick the chat can draw: clock, single tick, double tick and
     * the error mark.
     */
    private val DELIVERY_STATE_CHAT = listOf(
        Triple(true, "Still on its way to the server.", "2025-06-13T08:20:00Z"),
        Triple(true, "The server has this one.", "2025-06-13T08:21:00Z"),
        Triple(true, "You read this one.", "2025-06-13T08:22:00Z"),
        Triple(true, "This one never made it.", "2025-06-13T08:23:00Z"),
    )

    private val DELIVERY_STATES = listOf(
        com.patbaumgartner.android.stringtelephone.chat.SendState.PENDING,
        com.patbaumgartner.android.stringtelephone.chat.SendState.SENT,
        com.patbaumgartner.android.stringtelephone.chat.SendState.READ,
        com.patbaumgartner.android.stringtelephone.chat.SendState.FAILED,
    )

    /**
     * The image-message preview (Phase 6): one received photo plus text around
     * it, so the bubble, its placeholder and the meta row are visible together.
     */
    private val IMAGE_CHAT = listOf(
        Triple(false, "Here is the picture from the hike:", "2025-06-13T09:40:00Z"),
        Triple(true, "Looks great — thanks!", "2025-06-13T09:41:00Z"),
    )

    fun viewModel(
        context: Context,
        callEngine: CallEngine = FakeCallEngine(),
        deliveryStates: Boolean = false,
        chatImage: Boolean = false,
    ): AppViewModel {
        val cache = ChatCache()
        val conversation: List<Triple<Boolean, String, String>> = when {
            chatImage -> IMAGE_CHAT
            deliveryStates -> DELIVERY_STATE_CHAT
            else -> PREVIEW_CHAT
        }
        conversation.forEachIndexed { index, (fromMe, content, at) ->
            val state = when {
                chatImage -> com.patbaumgartner.android.stringtelephone.chat.SendState.SENT
                deliveryStates -> DELIVERY_STATES[index]
                index == conversation.lastIndex -> com.patbaumgartner.android.stringtelephone.chat.SendState.PENDING
                index == conversation.lastIndex - 1 -> com.patbaumgartner.android.stringtelephone.chat.SendState.FAILED
                else -> com.patbaumgartner.android.stringtelephone.chat.SendState.SENT
            }
            val key = "preview-" + index
            cache.append(
                "bob",
                com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
                    key, key, fromMe, content, java.time.Instant.parse(at).toEpochMilli(), state,
                    readAtEpochMs = if (state == com.patbaumgartner.android.stringtelephone.chat.SendState.READ) {
                        java.time.Instant.parse("2025-06-13T08:25:00Z").toEpochMilli()
                    } else {
                        null
                    },
                ),
            )
        }
        if (chatImage) {
            cache.append(
                "bob",
                com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
                    serverId = "preview-media",
                    clientKey = "preview-media",
                    fromMe = false,
                    content = "",
                    sentAtEpochMs = java.time.Instant.parse("2025-06-13T09:40:30Z").toEpochMilli(),
                    state = com.patbaumgartner.android.stringtelephone.chat.SendState.SENT,
                    type = "IMAGE",
                    mediaId = "preview-media-1",
                    width = 4032,
                    height = 3024,
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
