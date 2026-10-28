package com.patbaumgartner.android.stringtelephone.ui

import android.content.Context
import com.patbaumgartner.android.stringtelephone.call.AudioRoute
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.call.CameraFacing
import com.patbaumgartner.android.stringtelephone.call.EngineConfig
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.security.CredentialStore
import okhttp3.OkHttpClient
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

    /** Whether each entry in [calls] asked for video. */
    val videoCalls = mutableListOf<Boolean>()
    val routed = mutableListOf<AudioRoute>()
    var hungUp = false
        private set
    var acceptedWithVideo: Boolean? = null
        private set
    val videoToggles = mutableListOf<Boolean>()

    /** True once the background policy removed the video stream. */
    var releasedVideo = false
        private set
    var cameraFlips = 0
        private set
    var attachedSurfaces: Pair<Any?, Any?>? = null
        private set

    override fun call(remoteUser: String, withVideo: Boolean) {
        calls += remoteUser
        videoCalls += withVideo
        _state.value = CallSessionInfo(
            remoteUser = remoteUser,
            state = CallState.OUTGOING,
            audioRoute = if (withVideo) AudioRoute.SPEAKER else AudioRoute.EARPIECE,
            videoOffered = withVideo,
            videoEnabled = withVideo,
        )
    }

    override fun accept(withVideo: Boolean) {
        acceptedWithVideo = withVideo
        _state.value = _state.value.copy(
            videoOffered = _state.value.videoOffered || withVideo,
            videoEnabled = withVideo,
        )
    }

    override fun reject() { rejected = true }
    override fun hangUp() { hungUp = true }
    override fun setMute(muted: Boolean) {}
    override fun setAudioRoute(route: AudioRoute) {
        routed += route
        _state.value = _state.value.copy(audioRoute = route)
    }

    override fun setVideoEnabled(enabled: Boolean) {
        videoToggles += enabled
        _state.value = _state.value.copy(
            videoOffered = _state.value.videoOffered || enabled,
            videoEnabled = enabled,
        )
    }

    override fun releaseVideo() {
        releasedVideo = true
        _state.value = _state.value.copy(videoOffered = false, videoEnabled = false, videoActive = false)
    }

    override fun switchCamera() {
        cameraFlips++
        _state.value = _state.value.copy(
            cameraFacing = if (_state.value.cameraFacing == CameraFacing.FRONT) {
                CameraFacing.BACK
            } else {
                CameraFacing.FRONT
            },
        )
    }

    override fun attachVideoSurfaces(remote: Any?, preview: Any?) {
        attachedSurfaces = remote to preview
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
    val bobAsks = listOf(
        "Anything new on your side?",
        "How was your day?",
        "Did you see the match yesterday?",
        "That is a lot of birds on the terrace. Do you feed them?",
        "Which camera did you use for those shots?",
        "We should finally meet in person. When are you free?",
        "I am sending over the invitations today.",
        "The house was quiet when I came home.",
        "Books or a walk after lunch? I choose walk.",
        "Do you still have the original photo?",
        "The trail agent said the condition improved.",
        "Last one: are you in for a run on Saturday?",
    )
    val aliceAnswers = listOf(
        "Nothing new besides the coffee, honestly.",
        "Good, busy. Sending you the photos tonight.",
        "Only the highlights, but yes!",
        "No, they just come for the seeds. I refill weekly.",
        "All of them with a compact mirrorless, manual glass.",
        "Saturday morning works. Coffee after?",
        "Perfect. The address has not changed.",
        "The same here. I watered the balcony plants.",
        "Walk it is. Bring the good shoes.",
        "I do — the folder is on my desk.",
        "Then Sunday it is.",
        "Count me in. I will bring the water bottles.",
    )
    bobAsks.forEachIndexed { round, ask ->
        add(Triple(false, ask, "2025-06-13T08:${(10 + round).toString().padStart(2, '0')}:00Z"))
        add(
            Triple(
                true,
                aliceAnswers[round] +
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

    /**
     * The reaction preview (Phase 8): single and multi-author chips, the
     * signed-in user's own chip highlighted, one chip on an incoming bubble.
     */
    private val REACTIONS_CHAT = listOf(
        Triple(false, "Saturday morning works. Coffee after?", "2025-06-13T08:26:00Z"),
        Triple(true, "Perfect. See you at the usual place.", "2025-06-13T08:22:00Z"),
    )

    /** Who reacted to which preview message: index to (author → emoji). */
    private val REACTIONS = listOf(
        mapOf("bob" to "👍", "alice" to "👍", "carol" to "🔥"),
        mapOf("bob" to "❤️"),
    )

    /**
     * The reply preview (Phase 9): a received question and the outgoing
     * reply quoting it, so the quoted snapshot and the reply bubble are
     * visible together.
     */
    private val REPLY_CHAT = listOf(
        Triple(false, "Can you make it on Saturday afternoon?", "2025-06-13T09:40:00Z"),
        Triple(true, "Yes, 2pm works — see you at the usual place.", "2025-06-13T09:41:00Z"),
    )

    /** Which preview message index replies to which earlier server id. */
    private val REPLIES = mapOf(1 to "preview-0")

    /** The voice preview (Phase 11): an incoming voice message among text. */
    private val VOICE_CHAT = listOf(
        Triple(false, "Can't talk now — listen to this:", "2025-06-13T09:40:00Z"),
        Triple(true, "Got it, replying tonight.", "2025-06-13T09:41:00Z"),
    )

    /** The voice preview's waveform bars and duration. */
    private val VOICE_WAVEFORM = "12,38,55,72,40,64,88,30,52,20,45,76,58,35,15,68,90,44,26,60,82,38,18,50"

    /**
     * The deletion preview (Phase 10): one outgoing tombstone ("deleted for
     * both" placeholder) among live messages, plus one message the peer
     * deleted, so both sides' rendering is visible in one screen.
     */
    private val DELETION_CHAT = listOf(
        Triple(false, "Did you already send the report?", "2025-06-13T09:30:00Z"),
        Triple(true, "Yes, this morning — one moment, checking…", "2025-06-13T09:31:00Z"),
        Triple(false, "Wrong window, sorry — ignore that last one.", "2025-06-13T09:32:00Z"),
    )

    /** Which preview message is a tombstone, with its deletion stamp. */
    private val DELETIONS: Map<Int, Pair<Long, String>> = mapOf(2 to Pair(1_750_000_000_000L, "alice"))

    fun viewModel(
        context: Context,
        callEngine: CallEngine = FakeCallEngine(),
        deliveryStates: Boolean = false,
        chatImage: Boolean = false,
        chatReactions: Boolean = false,
        chatReply: Boolean = false,
        chatDeletion: Boolean = false,
        chatVoice: Boolean = false,
        lastSeen: Boolean = false,
    ): AppViewModel {
        val cache = ChatCache()
        val conversation: List<Triple<Boolean, String, String>> = when {
            chatVoice -> VOICE_CHAT
            chatDeletion -> DELETION_CHAT
            chatReply -> REPLY_CHAT
            chatReactions -> REACTIONS_CHAT
            chatImage -> IMAGE_CHAT
            deliveryStates -> DELIVERY_STATE_CHAT
            else -> PREVIEW_CHAT
        }
        conversation.forEachIndexed { index, (fromMe, content, at) ->
            val state = when {
                chatImage || chatReactions || chatReply || chatDeletion || chatVoice -> com.patbaumgartner.android.stringtelephone.chat.SendState.SENT
                deliveryStates -> DELIVERY_STATES[index]
                index == conversation.lastIndex -> com.patbaumgartner.android.stringtelephone.chat.SendState.PENDING
                index == conversation.lastIndex - 1 -> com.patbaumgartner.android.stringtelephone.chat.SendState.FAILED
                else -> com.patbaumgartner.android.stringtelephone.chat.SendState.SENT
            }
            val key = "preview-" + index
            val tombstone = if (chatDeletion) DELETIONS[index] else null
            cache.append(
                "bob",
                com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
                    key, key, fromMe, content, java.time.Instant.parse(at).toEpochMilli(), state,
                    readAtEpochMs = if (state == com.patbaumgartner.android.stringtelephone.chat.SendState.READ) {
                        java.time.Instant.parse("2025-06-13T08:25:00Z").toEpochMilli()
                    } else {
                        null
                    },
                    reactions = if (chatReactions) REACTIONS.getOrElse(index) { emptyMap() } else emptyMap(),
                    replyToId = if (chatReply) REPLIES[index] else null,
                    deletedAtEpochMs = tombstone?.first,
                    deletedBy = tombstone?.second,
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
        if (chatVoice) {
            cache.append(
                "bob",
                com.patbaumgartner.android.stringtelephone.chat.ChatMessage(
                    serverId = "preview-voice",
                    clientKey = "preview-voice",
                    fromMe = false,
                    content = "",
                    sentAtEpochMs = java.time.Instant.parse("2025-06-13T09:40:30Z").toEpochMilli(),
                    state = com.patbaumgartner.android.stringtelephone.chat.SendState.SENT,
                    type = "VOICE",
                    mediaId = "preview-voice-1",
                    durationMs = 42_000,
                    waveform = VOICE_WAVEFORM,
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
            repositoryOverride = StringTelephoneRepository(
                // A real client pointed at the in-test media server: only the
                // image screenshot fetches anything, and the base URL is what
                // makes ImageBubbleContent resolve a thumbnail URL at all.
                apiProvider = {
                    CompanionApiClient(
                        PreviewMediaServer.baseUrl(),
                        { requireNotNull(store.apiToken()) },
                        OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build(),
                    )
                },
                chatCache = cache,
            ),
            buildInfo = com.patbaumgartner.android.stringtelephone.di.BuildInfo("0.1.0", "1a2b3c4d5e"),
        )
        container.repository.seedForPreviews(
            me = com.patbaumgartner.android.stringtelephone.network.MeResult("alice", "Alice"),
            contacts = if (lastSeen) {
                // Phase 13: the offline contact carries a last-seen stamp; a
                // fixed minute so the rendered label is deterministic. Carol
                // is "last seen today", dave shows the plain online label.
                listOf(
                    com.patbaumgartner.android.stringtelephone.core.ContactDto("bob", "Bob", "online"),
                    com.patbaumgartner.android.stringtelephone.core.ContactDto(
                        "carol",
                        "Carol",
                        "offline",
                        lastSeenEpochMs = java.time.Instant.parse("2025-06-13T09:05:00Z").toEpochMilli(),
                    ),
                    com.patbaumgartner.android.stringtelephone.core.ContactDto("dave", "Dave", "online"),
                )
            } else {
                listOf(
                    com.patbaumgartner.android.stringtelephone.core.ContactDto("bob", "Bob", "online"),
                    com.patbaumgartner.android.stringtelephone.core.ContactDto("carol", "Carol", "offline"),
                    com.patbaumgartner.android.stringtelephone.core.ContactDto("dave", "Dave", "online"),
                )
            },
            unread = mapOf("carol" to 2),
        )
        val viewModel = AppViewModel(container)
        return viewModel
    }
}
