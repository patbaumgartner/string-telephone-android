package com.patbaumgartner.android.stringtelephone.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * User-visible foreground service holding the microphone FGS type for
 * the duration of a call (ADR-0006). It keeps SIP registration and the audio
 * path alive while the app is backgrounded; it never cold-starts ringing.
 */
class CallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val peer = intent?.getStringExtra(EXTRA_PEER).orEmpty()
        createChannel()
        // A microphone FGS may only start while the app is visible; otherwise skip it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(peer),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(peer))
            }
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        val engine = stringTelephoneContainer.callEngine
        engine.state
            .onEach { info ->
                if (info.state == CallState.IDLE) stopSelf()
            }
            .launchIn(scope)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_call_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(peer: String): Notification {
        createChannel()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_call_channel))
            .setContentText(peer)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(getColor(R.color.notification_accent))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "stringtelephone_calls"
        const val NOTIFICATION_ID = 42
        const val EXTRA_PEER = "peer"

        /** Start-or-refresh the call notification; safe while a call is active. */
        fun start(context: Context, peer: String) {
            val intent = Intent(context, CallForegroundService::class.java).putExtra(EXTRA_PEER, peer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
