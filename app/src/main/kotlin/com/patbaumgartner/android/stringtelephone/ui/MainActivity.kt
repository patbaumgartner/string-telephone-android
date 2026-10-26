package com.patbaumgartner.android.stringtelephone.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.patbaumgartner.android.stringtelephone.notify.AppNotifier
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(this) }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshIncomingCallAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppNotifier.ensureChannels(this)
        askForNotifications()
        setContent {
            StringTelephoneTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(viewModel)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.screen.collect { screen -> showOverLockScreen(screen is AppScreen.Call) }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        stringTelephoneContainer.session.onAppVisible()
    }

    override fun onResume() {
        super.onResume()
        refreshIncomingCallAccess()
    }

    override fun onStop() {
        stringTelephoneContainer.session.onAppHidden()
        // A locked voice take is hands-free, so it outlives the composer. Android
        // hands a backgrounded app silence instead of microphone input, so the
        // take would only record nothing; discard it rather than send a silent
        // message. This is a no-op when nothing is recording.
        viewModel.cancelVoiceRecording()
        super.onStop()
    }

    /** Settings can change while the app is away, so re-check on every return. */
    private fun refreshIncomingCallAccess() {
        viewModel.updateIncomingCallAccess(AppNotifier.incomingCallAccess(this))
    }

    private fun handleLaunch(intent: Intent?) {
        viewModel.handleLaunchAction(intent?.action, intent?.getStringExtra(EXTRA_PEER))
    }

    /** Lets a ringing or active call show above the lock screen, and only then. */
    private fun showOverLockScreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (enabled) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** Notification permission is required from Android 13; denied simply means silent. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AppNotifier.canPost(this)) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val ACTION_SHOW_CALL = "com.patbaumgartner.android.stringtelephone.action.SHOW_CALL"
        const val ACTION_ANSWER_CALL = "com.patbaumgartner.android.stringtelephone.action.ANSWER_CALL"
        const val ACTION_OPEN_CHAT = "com.patbaumgartner.android.stringtelephone.action.OPEN_CHAT"
        const val ACTION_OPEN_CONTACTS = "com.patbaumgartner.android.stringtelephone.action.OPEN_CONTACTS"
        const val EXTRA_PEER = "peer"
    }
}
