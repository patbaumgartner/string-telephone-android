package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.animation.Crossfade
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patbaumgartner.android.stringtelephone.R

@Composable
internal fun AppRoot() {
    val context = LocalContext.current
    val viewModel: AppViewModel = viewModel(factory = AppViewModel.factory(context))
    val screen by viewModel.screen.collectAsState()
    var microphoneDenied by remember { mutableStateOf(false) }
    val requestMicrophone = rememberMicrophonePermission { microphoneDenied = true }
    BackHandler(enabled = screen is AppScreen.Chat || screen is AppScreen.Settings) {
        viewModel.backToContacts()
    }
    Crossfade(targetState = screen, label = "screen") { current ->
        when (current) {
            is AppScreen.Onboarding -> OnboardingScreen(viewModel)
            is AppScreen.Contacts -> ContactsScreen(viewModel, onCall = { peer ->
                requestMicrophone { viewModel.startCall(peer) }
            })
            is AppScreen.Chat -> ChatScreen(peer = current.peer, viewModel = viewModel)
            is AppScreen.Call -> CallScreen(
                peer = current.peer,
                isIncoming = current.incoming,
                viewModel = viewModel,
                onAnswer = { requestMicrophone(viewModel::acceptCall) },
            )
            is AppScreen.Settings -> SettingsScreen(viewModel)
        }
    }
    if (microphoneDenied) {
        AlertDialog(
            onDismissRequest = { microphoneDenied = false },
            title = { Text(stringResource(R.string.microphone_permission_title)) },
            text = { Text(stringResource(R.string.microphone_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    microphoneDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                    )
                }) {
                    Text(stringResource(R.string.open_app_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { microphoneDenied = false }) {
                    Text(stringResource(R.string.not_now))
                }
            },
        )
    }
}
