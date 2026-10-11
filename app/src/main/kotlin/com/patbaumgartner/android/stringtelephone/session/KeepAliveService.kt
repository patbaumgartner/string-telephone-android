package com.patbaumgartner.android.stringtelephone.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer
import com.patbaumgartner.android.stringtelephone.ui.MainActivity

/**
 * Low-priority foreground service that keeps the process, SIP registration and
 * realtime socket alive while the app is closed, so calls ring and messages
 * notify without push infrastructure (ADR-0006). It runs only while signed in,
 * and it cannot survive a force-stop or reboot.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = stringTelephoneContainer
        // startForeground must come first: a started foreground service that stops
        // without it crashes the process.
        if (!enterForeground() || !container.credentialStore.hasCredentials()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        container.session.start()
        container.session.resume()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** Background start limits (Android 12+) can refuse a sticky restart; then we just stop. */
    private fun enterForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_keep_alive_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_keep_alive_title))
            .setContentText(getString(R.string.notification_keep_alive_text))
            .setSmallIcon(R.drawable.ic_call)
            .setColor(getColor(R.color.notification_accent))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "stringtelephone_connection"
        const val NOTIFICATION_ID = 43

        @Volatile
        var running = false
            private set

        /** Starts the service once; repeated calls while it runs are ignored. */
        fun start(context: Context) {
            if (running) return
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
