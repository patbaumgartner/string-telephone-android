package com.patbaumgartner.android.stringtelephone.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallSessionInfo
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.core.CallState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.network.MeResult
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import com.patbaumgartner.android.stringtelephone.notify.AppNotifier
import com.patbaumgartner.android.stringtelephone.ui.FakeCallEngine
import com.patbaumgartner.android.stringtelephone.ui.FakeCredentialStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
class SessionControllerTest {
    private class FakeStream : RealtimeStream {
        val flow = MutableSharedFlow<RealtimeEvent>(replay = 16)
        override val events = flow
        override fun connect() = Unit
        override fun disconnect() = Unit
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = FakeCredentialStore()
    private val engine = FakeCallEngine()
    private val stream = FakeStream()
    private val repository = StringTelephoneRepository({ null }, ChatCache(), { stream })
    private val controller = SessionController(context, store, repository, engine)

    private val notifications: List<StatusBarNotification>
        get() = context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.save("https://phone.example.com", "alice", "api-token", "sip-password")
        controller.start()
        repository.seedForPreviews(MeResult("alice", "Alice"), listOf(ContactDto("bob", "Bob", "online")))
        idle()
    }

    @After
    fun tearDown() {
        controller.signOut()
        idle()
    }

    @Test
    fun `verified session starts SIP and the keep-alive service`() {
        assertEquals("sip-password", engine.startedConfig?.sipSecret)
        val started = shadowOf(context).nextStartedService
        assertEquals(KeepAliveService::class.java.name, started.component?.className)
    }

    @Test
    fun `incoming call rings through a notification while the app is closed and becomes missed`() {
        emit(CallEvent.IncomingCall("bob"))
        val incoming = notifications.single { it.notification.channelId == AppNotifier.INCOMING_CALL_CHANNEL_ID }
        assertEquals("Bob is calling", incoming.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_CALL, incoming.notification.category)

        emit(CallEvent.CallEnded)
        assertTrue(notifications.none { it.notification.channelId == AppNotifier.INCOMING_CALL_CHANNEL_ID })
        val missed = notifications.single { it.notification.channelId == AppNotifier.CALL_CHANNEL_ID }
        assertEquals("Missed call from Bob", missed.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `visible app shows the call itself and a declined call is not missed`() {
        controller.onAppVisible()
        emit(CallEvent.IncomingCall("bob"))
        assertTrue(notifications.isEmpty())

        engine.setState(CallSessionInfo("bob", CallState.RINGING))
        controller.onAppHidden()
        assertEquals(1, notifications.count { it.notification.channelId == AppNotifier.INCOMING_CALL_CHANNEL_ID })

        controller.onCallDeclined()
        emit(CallEvent.CallEnded)
        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `messages notify per conversation unless that chat is open`() {
        repository.startRealtime()
        runBlocking { stream.flow.emit(RealtimeEvent.NewMessage("m1", "bob", "alice", "hello", 1_000, "k1")) }
        await { notifications.any { it.tag == "bob" } }
        val message = notifications.single { it.tag == "bob" }.notification
        assertEquals("Bob", message.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(1, repository.unread.value["bob"])

        controller.onAppVisible()
        controller.onChatShown("bob")
        assertTrue(notifications.none { it.tag == "bob" })
        assertNull(repository.unread.value["bob"])

        runBlocking { stream.flow.emit(RealtimeEvent.NewMessage("m2", "bob", "alice", "seen", 2_000, "k2")) }
        await { repository.history("bob").size == 2 }
        idle()
        assertTrue(notifications.none { it.tag == "bob" })

        controller.onAppHidden()
        runBlocking { stream.flow.emit(RealtimeEvent.NewMessage("m3", "bob", "alice", "later", 3_000, "k3")) }
        await { notifications.any { it.tag == "bob" } }
    }

    @Test
    fun `sign out stops SIP, forgets credentials and clears notifications`() {
        emit(CallEvent.IncomingCall("bob"))
        assertFalse(notifications.isEmpty())
        controller.signOut()
        idle()
        assertNull(engine.startedConfig)
        assertFalse(store.hasCredentials())
        assertTrue(notifications.isEmpty())
    }

    private fun emit(event: CallEvent) {
        runBlocking { engine.events.emit(event) }
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "Condition did not hold within five seconds")
    }
}
