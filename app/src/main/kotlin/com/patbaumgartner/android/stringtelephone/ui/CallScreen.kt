package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.call.AudioRoute
import com.patbaumgartner.android.stringtelephone.call.CallFailure
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun CallScreen(
    peer: String,
    isIncoming: Boolean,
    viewModel: AppViewModel,
    onAnswer: () -> Unit = viewModel::acceptCall,
) {
    val call by viewModel.callState.collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val failure = viewModel.callFailure

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        Spacer(Modifier.weight(1f))

        Text(
            viewModel.displayNameOf(peer, contactsState.contacts),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(12.dp))
        val stateLabel = if (failure != null) callFailureLabel(failure) else when (call.state) {
            CallState.RINGING -> stringResource(if (isIncoming) R.string.call_incoming else R.string.call_outgoing)
            CallState.OUTGOING, CallState.REGISTERING, CallState.READY, CallState.CONNECTING ->
                stringResource(R.string.call_outgoing)
            CallState.ACTIVE, CallState.ENDING -> stringResource(R.string.call_active)
            CallState.FAILED -> stringResource(R.string.call_failed)
            CallState.IDLE -> stringResource(R.string.call_ended)
        }
        Text(
            stateLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = StringTelephoneTheme.accents.textMuted,
            textAlign = TextAlign.Center,
        )
        val startedAt = call.startedAtEpochMs
        if (call.state == CallState.ACTIVE && startedAt != null) {
            Spacer(Modifier.height(8.dp))
            ElapsedTimer(startedAtEpochMs = startedAt)
        }

        Spacer(Modifier.weight(1f))

        // horizontalScroll keeps all five controls reachable in narrow
        // split-screen windows instead of clipping the hang-up button.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (failure != null) {
                CircleAction(
                    onClick = viewModel::dismissCallFailure,
                    icon = StringTelephoneIcons.Stop,
                    contentDescription = stringResource(R.string.call_close),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (isIncoming && call.state == CallState.RINGING) {
                CircleAction(
                    onClick = viewModel::rejectCall,
                    icon = StringTelephoneIcons.Stop,
                    contentDescription = stringResource(R.string.call_reject),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
                CircleAction(
                    onClick = onAnswer,
                    icon = StringTelephoneIcons.Call,
                    contentDescription = stringResource(R.string.call_answer),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                CircleAction(
                    onClick = viewModel::toggleMute,
                    icon = if (call.muted) StringTelephoneIcons.MicOff else StringTelephoneIcons.Mic,
                    contentDescription = stringResource(
                        if (call.muted) R.string.call_unmute else R.string.call_mute,
                    ),
                    containerColor = if (call.muted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (call.muted) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { selected = call.muted },
                )
                AudioRoute.entries.filter { it in call.availableAudioRoutes }.forEach { route ->
                    val routeSelected = call.audioRoute == route
                    CircleAction(
                        onClick = { viewModel.setAudioRoute(route) },
                        icon = when (route) {
                            AudioRoute.EARPIECE -> StringTelephoneIcons.Call
                            AudioRoute.SPEAKER -> StringTelephoneIcons.Speaker
                            AudioRoute.BLUETOOTH -> StringTelephoneIcons.Bluetooth
                        },
                        contentDescription = stringResource(
                            when (route) {
                                AudioRoute.EARPIECE -> R.string.call_earpiece
                                AudioRoute.SPEAKER -> R.string.call_speaker
                                AudioRoute.BLUETOOTH -> R.string.call_bluetooth
                            },
                        ),
                        containerColor = if (routeSelected) Color(0xFF303030)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (routeSelected) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { selected = routeSelected },
                    )
                }
                CircleAction(
                    onClick = viewModel::hangUp,
                    icon = StringTelephoneIcons.Stop,
                    contentDescription = stringResource(R.string.call_hang_up),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (failure != null) "" else when (call.state) {
                CallState.RINGING -> stringResource(R.string.state_call_ringing)
                CallState.OUTGOING, CallState.REGISTERING, CallState.READY, CallState.CONNECTING ->
                    stringResource(R.string.state_call_ringing_out)
                CallState.ACTIVE -> stringResource(R.string.state_call_active)
                CallState.ENDING -> stringResource(R.string.call_ended)
                CallState.FAILED -> stringResource(R.string.call_failed)
                CallState.IDLE -> ""
            },
            style = MaterialTheme.typography.labelLarge,
            color = StringTelephoneTheme.accents.brand,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun callFailureLabel(failure: CallFailure): String = stringResource(
    when (failure) {
        CallFailure.BUSY -> R.string.call_busy
        CallFailure.DECLINED -> R.string.call_failure_declined
        CallFailure.UNAVAILABLE -> R.string.call_failure_unavailable
        CallFailure.NO_ANSWER -> R.string.call_failure_no_answer
        CallFailure.NOT_CONNECTED -> R.string.call_failure_not_connected
        CallFailure.OTHER -> R.string.call_failed
    },
)

@Composable
private fun ElapsedTimer(startedAtEpochMs: Long) {
    var now by remember { mutableLongStateOf(startedAtEpochMs) }
    LaunchedEffect(startedAtEpochMs) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = ((now - startedAtEpochMs) / 1000).coerceAtLeast(0)
    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.headlineSmall,
        color = StringTelephoneTheme.accents.textMuted,
    )
}
