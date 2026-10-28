package com.patbaumgartner.android.stringtelephone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.di.AppContainer

/**
 * Test-only launcher used by screenshot tests. Renders one screen selected by
 * the [EXTRA_ROUTE] integer extra, with a seeded preview ViewModel.
 */
class ScreenshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.getIntExtra(EXTRA_ROUTE, ScreenshotRoute.CONTACTS.ordinal)
        val peer = intent.getStringExtra(EXTRA_PEER) ?: "bob"
        val viewModel = if (route == ScreenshotRoute.ONBOARDING.ordinal) {
            val repository = StringTelephoneRepository({ null }, ChatCache())
            if (intent.getBooleanExtra(EXTRA_ERROR, false)) {
                repository.reportSignInError(SignInError.AUTHENTICATION)
            }
            AppViewModel(
                AppContainer(
                    applicationContext,
                    credentialStoreOverride = FakeCredentialStore(),
                    callEngineOverride = FakeCallEngine(),
                    repositoryOverride = repository,
                ),
            ).apply {
                onServerUrlChanged("")
                onUsernameChanged("")
                onPasswordChanged("")
                onSipSecretChanged("")
            }
        } else {
            val videoCall = route == ScreenshotRoute.CALL_VIDEO.ordinal
            PreviewFactory.viewModel(
                applicationContext,
                FakeCallEngine(
                    com.patbaumgartner.android.stringtelephone.call.CallSessionInfo(
                        peer,
                        if (videoCall) {
                            com.patbaumgartner.android.stringtelephone.core.CallState.ACTIVE
                        } else {
                            com.patbaumgartner.android.stringtelephone.core.CallState.OUTGOING
                        },
                        muted = !videoCall,
                        audioRoute = com.patbaumgartner.android.stringtelephone.call.AudioRoute.SPEAKER,
                        availableAudioRoutes = setOf(
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.EARPIECE,
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.SPEAKER,
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.BLUETOOTH,
                        ),
                        // The elapsed timer would make the PNG differ on every
                        // run; the video fixture leaves it off on purpose.
                        startedAtEpochMs = null,
                        videoOffered = videoCall,
                        videoEnabled = videoCall,
                        videoActive = false,
                    ),
                ),
                deliveryStates = route == ScreenshotRoute.CHAT_DELIVERY_STATES.ordinal,
                chatImage = route == ScreenshotRoute.CHAT_IMAGE.ordinal,
                chatReactions = route == ScreenshotRoute.CHAT_REACTIONS.ordinal,
                chatReply = route == ScreenshotRoute.CHAT_REPLY.ordinal,
                chatDeletion = route == ScreenshotRoute.CHAT_DELETION.ordinal,
                chatVoice = route == ScreenshotRoute.CHAT_VOICE.ordinal,
                lastSeen = route == ScreenshotRoute.CONTACTS_LAST_SEEN.ordinal,
            ).apply {
                if (intent.getBooleanExtra(EXTRA_ERROR, false)) {
                    updateIncomingCallAccess(com.patbaumgartner.android.stringtelephone.notify.IncomingCallAccess.FULL_SCREEN_OFF)
                }
            }
        }
        setContent {
            StringTelephoneTheme(darkTheme = intent.getBooleanExtra(EXTRA_DARK, false)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (route) {
                        ScreenshotRoute.ONBOARDING.ordinal -> OnboardingScreen(viewModel)
                        ScreenshotRoute.CONTACTS.ordinal -> ContactsScreen(viewModel)
                        ScreenshotRoute.CONTACTS_LAST_SEEN.ordinal -> ContactsScreen(viewModel)
                        ScreenshotRoute.CHAT.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_SCROLLBAR.ordinal ->
                            ChatScreen(peer, viewModel, scrollbarVisible = true)
                        ScreenshotRoute.CHAT_DELIVERY_STATES.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_IMAGE.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_REACTIONS.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_REPLY.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_DELETION.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_VOICE.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CALL.ordinal -> CallScreen(peer, isIncoming = false, viewModel = viewModel)
                        ScreenshotRoute.CALL_VIDEO.ordinal -> CallScreen(peer, isIncoming = false, viewModel = viewModel)
                        else -> SettingsScreen(viewModel)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_PEER = "peer"
        const val EXTRA_DARK = "dark"
        const val EXTRA_ERROR = "error"
    }
}

enum class ScreenshotRoute { ONBOARDING, CONTACTS, CHAT, CALL, SETTINGS, CHAT_SCROLLBAR, CHAT_DELIVERY_STATES, CHAT_IMAGE, CHAT_REACTIONS, CHAT_REPLY, CHAT_DELETION, CHAT_VOICE, CALL_VIDEO, CONTACTS_LAST_SEEN }
