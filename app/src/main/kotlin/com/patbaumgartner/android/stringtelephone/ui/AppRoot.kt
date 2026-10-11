package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun AppRoot() {
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(LocalContext.current))
    val screen by viewModel.screen.collectAsState()
    Crossfade(targetState = screen, label = "screen") { current ->
        when (current) {
            is AppScreen.Onboarding -> OnboardingScreen(viewModel)
            is AppScreen.Contacts -> ContactsScreen(viewModel)
            is AppScreen.Chat -> ChatScreen(peer = current.peer, viewModel = viewModel)
            is AppScreen.Call -> CallScreen(
                peer = current.peer,
                isIncoming = current.incoming,
                viewModel = viewModel,
            )
            is AppScreen.Settings -> SettingsScreen(viewModel)
        }
    }
}
