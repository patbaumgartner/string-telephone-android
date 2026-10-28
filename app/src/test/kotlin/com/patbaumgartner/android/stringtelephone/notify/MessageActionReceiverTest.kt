package com.patbaumgartner.android.stringtelephone.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Quick reply from the notification shade (Phase 14): the message
 * notification carries a RemoteInput reply and a mark-as-read action, the
 * reply is sent through the repository's normal path without opening the
 * activity, and a failed send surfaces as a follow-up notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
class MessageActionReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val receiver = MessageActionReceiver()
    private val repository = context.stringTelephoneContainer.repository

    private val notifications: List<android.service.notification.StatusBarNotification>
        get() = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** An intent as the notification action would deliver it, with [text] as the RemoteInput result. */
    private fun quickReplyIntent(text: String): Intent {
        val intent = Intent(context, MessageActionReceiver::class.java)
            .setAction(MessageActionReceiver.ACTION_QUICK_REPLY)
            .putExtra(MessageActionReceiver.EXTRA_PEER, "bob")
        RemoteInput.addResultsToIntent(
            arrayOf(MessageActionReceiver.replyRemoteInput(context)),
            intent,
            Bundle().apply { putCharSequence(MessageActionReceiver.KEY_REPLY_TEXT, text) },
        )
        return intent
    }

    /** An intent with an action and peer but no RemoteInput results. */
    private fun actionIntent(action: String): Intent =
        Intent(context, MessageActionReceiver::class.java)
            .setAction(action)
            .putExtra(MessageActionReceiver.EXTRA_PEER, "bob")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
            idle()
        }
        error("condition not met within $timeoutMs ms")
    }

    /** Reads the queued started-activity intent, or null when none was started. */
    private fun nextStartedOrNull(): Intent? =
        runCatching { shadowOf(context).peekNextStartedActivity() }.getOrNull()

    @Test
    fun `message notifications offer inline reply and mark as read`() {
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)
        val notification = notifications.single().notification

        assertEquals(2, notification.actions.size)
        val reply = notification.actions[0]
        assertEquals(context.getString(R.string.notification_reply), reply.title.toString())
        assertEquals(MessageActionReceiver.KEY_REPLY_TEXT, reply.remoteInputs.single().resultKey)

        val markRead = notification.actions[1]
        assertEquals(context.getString(R.string.notification_mark_read), markRead.title.toString())
    }

    @Test
    fun `a quick reply sends through the repository without opening the activity`() {
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)
        val startedBefore = nextStartedOrNull()

        receiver.onReceive(context, quickReplyIntent("hi from the shade"))
        awaitUntil { repository.history("bob").isNotEmpty() }

        // The optimistic bubble went through the same path as the composer,
        // and since the test container has no server the delivery failed.
        assertEquals("hi from the shade", repository.history("bob").single().content)
        awaitUntil { repository.history("bob").single().state == SendState.FAILED }

        // The receiver never starts an activity.
        assertEquals(startedBefore, nextStartedOrNull())
    }

    @Test
    fun `a failed quick reply surfaces a follow-up notification`() {
        // The test container's repository has no server: every send fails.
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)
        receiver.onReceive(context, quickReplyIntent("will not arrive"))

        awaitUntil { notifications.size == 2 }
        val failure = notifications.single { it.id != MESSAGE_NOTIFICATION_ID }
        assertEquals(
            context.getString(R.string.notification_send_failed_title),
            failure.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun `mark as read never opens the activity`() {
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)
        val startedBefore = nextStartedOrNull()

        receiver.onReceive(context, actionIntent(MessageActionReceiver.ACTION_MARK_READ))
        idle()

        assertEquals(startedBefore, nextStartedOrNull())
    }

    @Test
    fun `quick reply without text does nothing`() {
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)

        receiver.onReceive(context, actionIntent(MessageActionReceiver.ACTION_QUICK_REPLY))
        idle()

        assertEquals(emptyList(), repository.history("bob"))
    }

    @Test
    fun `quick reply with only whitespace does not append a message`() {
        receiver.onReceive(context, quickReplyIntent("   "))
        idle()
        assertEquals(emptyList(), repository.history("bob"))
    }

    private companion object {
        /** The message notification's fixed id (mirrors AppNotifier). */
        const val MESSAGE_NOTIFICATION_ID = 50
    }
}
