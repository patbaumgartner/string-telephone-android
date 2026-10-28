package com.patbaumgartner.android.stringtelephone.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.NotificationManagerCompat
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.ui.MainActivity
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer

/**
 * Handles quick-reply and mark-as-read actions from message notifications
 * (Phase 14). The quick-reply path sends through the repository (same
 * optimistic-append + delivery path as the in-app composer) and shows a
 * follow-up notification on failure — it never opens the activity.
 */
class MessageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peer = intent.getStringExtra(EXTRA_PEER) ?: return
        val container = context.stringTelephoneContainer

        when (intent.action) {
            ACTION_QUICK_REPLY -> handleQuickReply(context, container, peer, intent)
            ACTION_MARK_READ -> handleMarkRead(context, container, peer)
        }
    }

    private fun handleQuickReply(context: Context, container: AppContainer, peer: String, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT, "")?.toString()?.trim()
        if (text.isNullOrEmpty()) return

        container.repository.send(peer, text) { success ->
            if (!success) {
                val notification = NotificationCompat.Builder(
                    context,
                    AppNotifier.MESSAGE_CHANNEL_ID,
                )
                    .setSmallIcon(R.drawable.ic_message)
                    .setColor(context.getColor(R.color.notification_accent))
                    .setContentTitle(context.getString(R.string.notification_send_failed_title))
                    .setContentText(context.getString(R.string.notification_send_failed_text))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            peer.hashCode() + 1,
                            Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_OPEN_CHAT)
                                .putExtra(MainActivity.EXTRA_PEER, peer)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .build()
                // Use notify(id, notification): tag-less so we don't need a stable int.
                // hashCode() of a stable string is deterministic and collision-free enough.
                runCatching {
                    NotificationManagerCompat.from(context)
                        .notify(peer.hashCode() + SEND_FAILED_ID_BASE, notification)
                }
            }
        }
    }

    private fun handleMarkRead(context: Context, container: AppContainer, peer: String) {
        container.repository.markConversationRead(peer, null)
        // The notification is left to fade naturally; the next incoming
        // message from this peer will refresh it, or the user opens the chat.
    }

    companion object {
        const val ACTION_QUICK_REPLY = "com.patbaumgartner.android.stringtelephone.action.QUICK_REPLY"
        const val ACTION_MARK_READ    = "com.patbaumgartner.android.stringtelephone.action.MARK_READ"
        const val EXTRA_PEER          = "peer"
        const val KEY_REPLY_TEXT      = "reply_text"

        /** Base id for per-peer send-failure notifications, away from the other ids. */
        const val SEND_FAILED_ID_BASE = 1000

        /**
         * PendingIntent intent-factory used by [AppNotifier.notifyConversation]
         * so the same peer key is used for both the quick-reply and mark-read
         * intents (requestCode = peer.hashCode(), matching the content intent).
         */
        fun quickReplyIntent(context: Context, peer: String): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                peer.hashCode(),
                Intent(context, MessageActionReceiver::class.java)
                    .setAction(ACTION_QUICK_REPLY)
                    .putExtra(EXTRA_PEER, peer),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun markReadIntent(context: Context, peer: String): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                peer.hashCode(),
                Intent(context, MessageActionReceiver::class.java)
                    .setAction(ACTION_MARK_READ)
                    .putExtra(EXTRA_PEER, peer),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Builds a RemoteInput for the quick-reply action. The label is shown
         * in the inline reply field; the results are extracted with
         * RemoteInput.getResultsFromIntent in [handleQuickReply].
         */
        fun replyRemoteInput(context: Context): RemoteInput {
            return RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel(context.getString(R.string.notification_reply_hint))
                .build()
        }
    }
}
