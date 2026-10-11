package com.patbaumgartner.android.stringtelephone.notify

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
class IncomingCallScreenTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private val systemCheck = AppNotifier.fullScreenIntentCheck

    @Before
    fun grantNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restoreSystemCheck() {
        AppNotifier.fullScreenIntentCheck = systemCheck
    }

    private fun allowFullScreen(allowed: Boolean) {
        AppNotifier.fullScreenIntentCheck = { allowed }
    }

    @Test
    fun `a ringing call opens the call screen full screen when Android allows it`() {
        allowFullScreen(true)

        AppNotifier.notifyIncomingCall(context, "carol", "Carol")

        val fullScreen = assertNotNull(notifications.activeNotifications.single().notification.fullScreenIntent)
        val launch = shadowOf(fullScreen).savedIntent
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertEquals(MainActivity.ACTION_SHOW_CALL, launch.action)
        assertEquals(IncomingCallAccess.READY, AppNotifier.incomingCallAccess(context))
        assertNull(AppNotifier.incomingCallSettingsIntent(context, IncomingCallAccess.READY))
    }

    @Test
    fun `without full-screen permission the call still rings and the app points to the setting`() {
        allowFullScreen(false)

        AppNotifier.notifyIncomingCall(context, "carol", "Carol")

        val notification = notifications.activeNotifications.single().notification
        assertNull(notification.fullScreenIntent)
        assertEquals(2, notification.actions.size)
        assertEquals(IncomingCallAccess.FULL_SCREEN_OFF, AppNotifier.incomingCallAccess(context))
        val settings = assertNotNull(
            AppNotifier.incomingCallSettingsIntent(context, IncomingCallAccess.FULL_SCREEN_OFF),
        )
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, settings.action)
        assertEquals(context.packageName, settings.data?.schemeSpecificPart)
    }

    @Test
    fun `denied notifications are reported before the full-screen setting`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        allowFullScreen(false)

        assertEquals(IncomingCallAccess.NOTIFICATIONS_OFF, AppNotifier.incomingCallAccess(context))
        val settings = assertNotNull(
            AppNotifier.incomingCallSettingsIntent(context, IncomingCallAccess.NOTIFICATIONS_OFF),
        )
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(context.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
