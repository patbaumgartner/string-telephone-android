package com.patbaumgartner.android.stringtelephone.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.AppScreen

/**
 * Central notification hub: channels for calls and messages, the
 * POST_NOTIFICATIONS runtime request, missed-call notifications, and grouped
 * message notifications. Missed-call and message notifications are only
 * produced while the process is alive (ADR-0006: no cold-start ringing).
 */
object AppNotifier {

    const val CALL_CHANNEL_ID = "stringtelephone_calls"
    const val MESSAGE_CHANNEL_ID = "stringtelephone_messages"

    private const val MISSED_CALL_NOTIFICATION_ID = 100
    private const val MESSAGE_NOTIFICATION_ID = 50
    private const val MESSAGE_GROUP = "stringtelephone_messages"

    /** Result of a permission ask; caller decides whether to post anyway. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CALL_CHANNEL_ID,
                context.getString(R.string.notification_call_channel),
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.notification_message_channel),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /**
     * Posts a missed-call notification for [peer]; replaces any previous one.
     * Permission is verified by [canPost] and a rejected grant is swallowed by
     * the runCatching below, so lint's inline check cannot see the guard.
     */
    @SuppressLint("MissingPermission")
    fun notifyMissedCall(context: Context, peer: String, displayPeer: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val contentIntent = contentIntent(context)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.notification_missed_call_title))
            .setContentText(context.getString(R.string.notification_missed_call_text, displayPeer))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(MISSED_CALL_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Shows or updates the grouped new-message notification.
     * [perPeerLines] maps peer display name to the latest message snippet;
     * [hiddenCount] counts messages beyond the previewed ones.
     * Permission is verified by [canPost] and a rejected grant is swallowed by
     * the runCatching below, so lint's inline check cannot see the guard.
     */
    @SuppressLint("MissingPermission")
    fun notifyMessages(
        context: Context,
        perPeerLines: List<Pair<String, String>>,
        hiddenCount: Int,
    ) {
        if (perPeerLines.isEmpty() || !canPost(context)) return
        ensureChannels(context)
        val nm = NotificationManagerCompat.from(context)
        val style = android.app.Notification.InboxStyle()
        for ((name, line) in perPeerLines.take(5)) {
            style.addLine("$name: $line")
        }
        if (hiddenCount > 0) {
            style.setSummaryText(context.getString(R.string.notification_more_messages, hiddenCount))
        }
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(context.getString(R.string.notification_new_message_title))
            .setContentText(perPeerLines.firstOrNull()?.let { (name, line) -> "$name: $line" }.orEmpty())
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(perPeerLines.joinToString("\n") { (name, line) -> "$name: $line" }),
            )
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(MESSAGE_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .setNumber(perPeerLines.sumOf { it.second.length } + hiddenCount)
            .build()
        runCatching { nm.notify(MESSAGE_NOTIFICATION_ID, notification) }
    }

    fun cancelMessageNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
    }

    fun cancelMissedCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(MISSED_CALL_NOTIFICATION_ID)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
