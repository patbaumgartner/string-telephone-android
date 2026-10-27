package com.patbaumgartner.android.stringtelephone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Camera gate mirroring the microphone gate. The caller passes what to do
 * when the permission is granted and what to do instead when it is denied —
 * a denied camera must fall back to audio, never block the call.
 */
@Composable
internal fun rememberCameraPermission(): (granted: () -> Unit, denied: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<() -> Unit, () -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val actions = pending
        pending = null
        if (allowed) actions?.first?.invoke() else actions?.second?.invoke()
    }
    return { granted, denied ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            granted()
        } else {
            pending = granted to denied
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}
