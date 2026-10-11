package com.patbaumgartner.android.stringtelephone.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import com.patbaumgartner.android.stringtelephone.call.CallFailure
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A failed outgoing call used to drop straight back to Contacts with no
 * explanation. The call screen now stays, says why, and then closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
class CallFailureNavigationTest {
    private val engine = FakeCallEngine()
    private val viewModel = PreviewFactory.viewModel(ApplicationProvider.getApplicationContext(), engine)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun emit(event: CallEvent) {
        runBlocking { engine.events.emit(event) }
        idle()
    }

    private fun startOutgoingCall(peer: String = "carol") {
        idle()
        viewModel.startCall(peer)
        idle()
        assertEquals(listOf(peer), engine.calls)
        assertEquals(AppScreen.Call(peer, incoming = false), viewModel.screen.value)
    }

    @Test
    fun failedOutgoingCallStaysOnTheCallScreenWithTheReason() {
        startOutgoingCall()
        emit(CallEvent.CallFailed("Temporarily Unavailable", CallFailure.UNAVAILABLE))

        assertEquals(AppScreen.Call("carol", incoming = false), viewModel.screen.value)
        assertEquals(CallFailure.UNAVAILABLE, viewModel.callFailure)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AppViewModel.CALL_FAILURE_VISIBLE_MS))
        assertEquals(AppScreen.Contacts, viewModel.screen.value)
        assertNull(viewModel.callFailure)
    }

    @Test
    fun closingTheFailureReturnsToContactsWithoutHangingUpAnotherCall() {
        startOutgoingCall()
        emit(CallEvent.CallFailed("Busy Here", CallFailure.BUSY))

        viewModel.hangUp()

        assertEquals(AppScreen.Contacts, viewModel.screen.value)
        assertNull(viewModel.callFailure)
        assertFalse(engine.hungUp)
    }

    @Test
    fun aNewCallClearsThePreviousFailure() {
        startOutgoingCall()
        emit(CallEvent.CallFailed("Busy Here", CallFailure.BUSY))
        engine.calls.clear()

        startOutgoingCall("bob")
        assertNull(viewModel.callFailure)
    }

    @Test
    fun normallyEndedCallsAndFailedIncomingCallsReturnImmediately() {
        startOutgoingCall()
        emit(CallEvent.CallEnded)
        assertEquals(AppScreen.Contacts, viewModel.screen.value)

        emit(CallEvent.IncomingCall("bob"))
        assertEquals(AppScreen.Call("bob", incoming = true), viewModel.screen.value)
        emit(CallEvent.CallFailed("Request Terminated", CallFailure.OTHER))
        assertEquals(AppScreen.Contacts, viewModel.screen.value)
        assertNull(viewModel.callFailure)
    }

    @Test
    fun settingsShowsVersionAndCommit() {
        val build = viewModel.buildInfo()
        assertEquals("0.1.0", build.versionName)
        assertEquals("1a2b3c4d5e", build.commit)
    }
}
