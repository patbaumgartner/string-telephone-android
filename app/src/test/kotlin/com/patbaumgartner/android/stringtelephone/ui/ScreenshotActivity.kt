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
            PreviewFactory.viewModel(
                applicationContext,
                FakeCallEngine(
                    com.patbaumgartner.android.stringtelephone.call.CallSessionInfo(
                        peer, com.patbaumgartner.android.stringtelephone.core.CallState.OUTGOING,
                        muted = true,
                        audioRoute = com.patbaumgartner.android.stringtelephone.call.AudioRoute.SPEAKER,
                        availableAudioRoutes = setOf(
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.EARPIECE,
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.SPEAKER,
                            com.patbaumgartner.android.stringtelephone.call.AudioRoute.BLUETOOTH,
                        ),
                    ),
                ),
                deliveryStates = route == ScreenshotRoute.CHAT_DELIVERY_STATES.ordinal,
                chatImage = route == ScreenshotRoute.CHAT_IMAGE.ordinal,
                chatReactions = route == ScreenshotRoute.CHAT_REACTIONS.ordinal,
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
                        ScreenshotRoute.CHAT.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_SCROLLBAR.ordinal ->
                            ChatScreen(peer, viewModel, scrollbarVisible = true)
                        ScreenshotRoute.CHAT_DELIVERY_STATES.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_IMAGE.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CHAT_REACTIONS.ordinal -> ChatScreen(peer, viewModel)
                        ScreenshotRoute.CALL.ordinal -> CallScreen(peer, isIncoming = false, viewModel = viewModel)
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

enum class ScreenshotRoute { ONBOARDING, CONTACTS, CHAT, CALL, SETTINGS, CHAT_SCROLLBAR, CHAT_DELIVERY_STATES, CHAT_IMAGE, CHAT_REACTIONS }
