package com.patbaumgartner.android.stringtelephone.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.call.CallForegroundService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ScreenshotTestApp::class)
class NotificationBrandingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `missed calls, incoming calls and messages use the cyan notification accent`() {
        AppNotifier.notifyMissedCall(context, "bob", "Bob")
        AppNotifier.notifyConversation(context, "bob", "Bob", listOf("Hello" to 1_000L), 1)
        AppNotifier.notifyIncomingCall(context, "carol", "Carol")
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(3, notifications.size)
        notifications.forEach { notification ->
            assertEquals(context.getColor(R.color.notification_accent), notification.notification.color)
        }
    }

    @Test
    fun `ongoing call uses the same cyan notification accent`() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent().putExtra(CallForegroundService.EXTRA_PEER, "bob"), 0, 1)
            val notification = assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(context.getColor(R.color.notification_accent), notification.color)
        } finally {
            controller.destroy()
        }
    }
}
