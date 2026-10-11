package com.patbaumgartner.android.stringtelephone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

/**
 * Test-only launcher used by screenshot tests. Renders one screen selected by
 * the [EXTRA_ROUTE] integer extra, with a seeded preview ViewModel.
 */
class ScreenshotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.getIntExtra(EXTRA_ROUTE, ScreenshotRoute.CONTACTS.ordinal)
        val peer = intent.getStringExtra(EXTRA_PEER) ?: "bob"
        setContent {
            StringTelephoneTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel = PreviewFactory.viewModel(applicationContext)
                    when (route) {
                        ScreenshotRoute.ONBOARDING.ordinal -> OnboardingScreen(viewModel)
                        ScreenshotRoute.CONTACTS.ordinal -> ContactsScreen(viewModel)
                        ScreenshotRoute.CHAT.ordinal -> ChatScreen(peer, viewModel)
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
    }
}

enum class ScreenshotRoute { ONBOARDING, CONTACTS, CHAT, CALL, SETTINGS }
