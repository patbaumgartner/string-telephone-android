package com.patbaumgartner.android.stringtelephone.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.MainActivity

/** What stops a ringing call from opening String Telephone automatically. */
enum class IncomingCallAccess { READY, NOTIFICATIONS_OFF, FULL_SCREEN_OFF }

/**
 * Central notification hub: channels, the incoming-call notification,
 * missed calls, and one message notification per conversation. They are only
 * produced while the process is alive; the keep-alive service keeps it alive
 * while signed in (ADR-0006: no cold-start ringing after force-stop or reboot).
 */
object AppNotifier {

    const val CALL_CHANNEL_ID = "stringtelephone_calls"
    const val INCOMING_CALL_CHANNEL_ID = "stringtelephone_incoming_calls"
    const val MESSAGE_CHANNEL_ID = "stringtelephone_messages"

    private const val MISSED_CALL_NOTIFICATION_ID = 100
    private const val INCOMING_CALL_NOTIFICATION_ID = 101
    private const val MESSAGE_NOTIFICATION_ID = 50

    private const val REQUEST_SHOW_CALL = 1
    private const val REQUEST_ANSWER = 2
    private const val REQUEST_DECLINE = 3
    private const val REQUEST_CONTACTS = 4

    /** Result of a permission ask; caller decides whether to post anyway. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Whether a ringing call can take over the screen like the phone app.
     * Android 14+ only honours full-screen intents after the user allows them.
     */
    fun canUseFullScreenIntent(context: Context): Boolean = fullScreenIntentCheck(context)

    /** Robolectric has no shadow for the Android 14 full-screen-intent check; tests replace it. */
    internal var fullScreenIntentCheck: (Context) -> Boolean = { context ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun incomingCallAccess(context: Context): IncomingCallAccess = when {
        !canPost(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
            IncomingCallAccess.NOTIFICATIONS_OFF
        !canUseFullScreenIntent(context) -> IncomingCallAccess.FULL_SCREEN_OFF
        else -> IncomingCallAccess.READY
    }

    /** The system settings page that fixes [access], or null when nothing is missing. */
    fun incomingCallSettingsIntent(context: Context, access: IncomingCallAccess): Intent? = when (access) {
        IncomingCallAccess.READY -> null
        IncomingCallAccess.NOTIFICATIONS_OFF ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        IncomingCallAccess.FULL_SCREEN_OFF ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.fromParts("package", context.packageName, null),
                )
            } else {
                null
            }
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CALL_CHANNEL_ID,
                context.getString(R.string.notification_call_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        // Silent: the SIP engine plays the device ringtone itself, also while the app is open.
        nm.createNotificationChannel(
            NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                context.getString(R.string.notification_incoming_call_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.notification_message_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /**
     * Shows the ringing call with Answer and Decline. Answer opens the app so
     * the microphone permission gate still applies. On locked devices the
     * full-screen intent shows the call screen when the OS allows it.
     */
    fun notifyIncomingCall(context: Context, peer: String, displayPeer: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val show = activityIntent(context, MainActivity.ACTION_SHOW_CALL, peer, REQUEST_SHOW_CALL)
        val answer = activityIntent(context, MainActivity.ACTION_ANSWER_CALL, peer, REQUEST_ANSWER)
        val decline = PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notification_incoming_call_title))
            .setContentText(context.getString(R.string.notification_incoming_call_text, displayPeer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(show)
        val canUseFullScreen = canUseFullScreenIntent(context)
        if (canUseFullScreen) builder.setFullScreenIntent(show, true)
        val styled = if (canUseFullScreen) {
            runCatching {
                // CallStyle requires a full-screen intent (or a call FGS) on Android 12+.
                builder.setStyle(
                    NotificationCompat.CallStyle.forIncomingCall(
                        Person.Builder().setName(displayPeer).setImportant(true).build(),
                        decline,
                        answer,
                    ),
                ).build()
            }.getOrNull()
        } else {
            null
        }
        val notification = styled ?: builder
            .setStyle(null)
            .addAction(R.drawable.ic_call, context.getString(R.string.call_reject), decline)
            .addAction(R.drawable.ic_call, context.getString(R.string.call_answer), answer)
            .build()
        post(context, null, INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    fun cancelIncomingCall(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    /** Posts a missed-call notification for [peer]; replaces any previous one. */
    fun notifyMissedCall(context: Context, peer: String, displayPeer: String) {
        if (!canPost(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notification_missed_call_title))
            .setContentText(context.getString(R.string.notification_missed_call_text, displayPeer))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, MainActivity.ACTION_OPEN_CONTACTS, peer, REQUEST_CONTACTS))
            .build()
        post(context, null, MISSED_CALL_NOTIFICATION_ID, notification)
    }

    /**
     * Shows or updates the notification for one conversation. [messages] are
     * the recent unseen (content, epoch ms) pairs from [peer], oldest first;
     * tapping opens that chat.
     */
    fun notifyConversation(
        context: Context,
        peer: String,
        displayPeer: String,
        messages: List<Pair<String, Long>>,
        unreadCount: Int,
    ) {
        if (messages.isEmpty() || !canPost(context)) return
        ensureChannels(context)
        val sender = Person.Builder().setName(displayPeer).setKey(peer).build()
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(context.getString(R.string.notification_you)).build(),
        )
        for ((content, at) in messages) style.addMessage(content, at, sender)
        val latest = messages.last()
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setColor(context.getColor(R.color.notification_accent))
            .setContentTitle(displayPeer)
            .setContentText(latest.first)
            .setWhen(latest.second)
            .setShowWhen(true)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(unreadCount)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(activityIntent(context, MainActivity.ACTION_OPEN_CHAT, peer, peer.hashCode()))
            .build()
        post(context, peer, MESSAGE_NOTIFICATION_ID, notification)
    }

    fun cancelConversation(context: Context, peer: String) {
        NotificationManagerCompat.from(context).cancel(peer, MESSAGE_NOTIFICATION_ID)
    }

    fun cancelMissedCallNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(MISSED_CALL_NOTIFICATION_ID)
    }

    /** Removes everything this app posted, e.g. after sign-out. */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * Permission is verified by [canPost] and a rejected grant is swallowed by
     * the runCatching below, so lint's inline check cannot see the guard.
     */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, tag: String?, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(tag, id, notification) }
    }

    private fun activityIntent(context: Context, action: String, peer: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(action)
            .putExtra(MainActivity.EXTRA_PEER, peer)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
