package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.animation.Crossfade
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.patbaumgartner.android.stringtelephone.R

@Composable
internal fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsState()
    val call by viewModel.callState.collectAsState()
    var microphoneDenied by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val requestMicrophone = rememberMicrophonePermission { microphoneDenied = true }
    val requestCamera = rememberCameraPermission()
    // Answer tapped in the incoming-call notification still passes the microphone
    // gate, and a video INVITE also needs the camera before it is accepted.
    LaunchedEffect(viewModel.pendingAnswer) {
        if (viewModel.pendingAnswer) {
            viewModel.consumePendingAnswer()
            requestMicrophone {
                if (call.videoOffered) {
                    requestCamera(
                        { viewModel.acceptCall(withVideo = true) },
                        { cameraDenied = true; viewModel.acceptCall() },
                    )
                } else {
                    viewModel.acceptCall()
                }
            }
        }
    }
    BackHandler(enabled = screen is AppScreen.Chat || screen is AppScreen.Settings || screen is AppScreen.GroupChat || screen is AppScreen.GroupDetail) {
        if (screen is AppScreen.GroupDetail) viewModel.backToContacts() else viewModel.backToContacts()
    }
    Crossfade(targetState = screen, label = "screen") { current ->
        when (current) {
            is AppScreen.Onboarding -> OnboardingScreen(viewModel)
            is AppScreen.Contacts -> ContactsScreen(viewModel, onCall = { peer ->
                requestMicrophone { viewModel.startCall(peer) }
            })
            is AppScreen.Groups -> GroupsScreen(viewModel)
            is AppScreen.GroupDetail -> GroupDetailScreen(
                group = viewModel.groupById(current.groupId),
                contacts = viewModel.contactsState().collectAsState().value.contacts,
                meUsername = viewModel.meUsername() ?: "",
                viewModel = viewModel,
            )
            is AppScreen.GroupChat -> GroupChatScreen(
                groupId = current.groupId,
                viewModel = viewModel,
            )
            is AppScreen.Chat -> ChatScreen(
                peer = current.peer,
                viewModel = viewModel,
                onCall = { peer -> requestMicrophone { viewModel.startCall(peer) } },
                onVideoCall = { peer ->
                    requestMicrophone {
                        requestCamera(
                            { viewModel.startCall(peer, withVideo = true) },
                            { cameraDenied = true; viewModel.startCall(peer) },
                        )
                    }
                },
            )
            is AppScreen.Call -> CallScreen(
                peer = current.peer,
                isIncoming = current.incoming,
                viewModel = viewModel,
                onAnswer = {
                    requestMicrophone {
                        if (call.videoOffered) {
                            requestCamera(
                                { viewModel.acceptCall(withVideo = true) },
                                { cameraDenied = true; viewModel.acceptCall() },
                            )
                        } else {
                            viewModel.acceptCall()
                        }
                    }
                },
                onToggleVideo = {
                    if (call.videoEnabled) {
                        viewModel.setVideoEnabled(false)
                    } else {
                        requestCamera(
                            { viewModel.setVideoEnabled(true) },
                            { cameraDenied = true },
                        )
                    }
                },
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
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
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
    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text = { Text(stringResource(R.string.camera_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    cameraDenied = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                    )
                }) {
                    Text(stringResource(R.string.open_app_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { cameraDenied = false }) {
                    Text(stringResource(R.string.not_now))
                }
            },
        )
    }
}
