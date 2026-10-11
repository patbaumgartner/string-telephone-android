package com.patbaumgartner.android.stringtelephone.call

import kotlin.test.Test
import kotlin.test.assertEquals

/** SIP final responses map to failures the call screen can explain. */
class CallFailureTest {

    @Test
    fun busyAndDeclinedComeFromTheirSipCodes() {
        assertEquals(CallFailure.BUSY, callFailureFor(486, "Busy", peerRang = false))
        assertEquals(CallFailure.BUSY, callFailureFor(600, "Unknown", peerRang = true))
        assertEquals(CallFailure.DECLINED, callFailureFor(603, "Declined", peerRang = true))
    }

    @Test
    fun asteriskTemporarilyUnavailableMeansOfflineUnlessThePeerRang() {
        assertEquals(CallFailure.UNAVAILABLE, callFailureFor(480, "TemporarilyUnavailable", peerRang = false))
        assertEquals(CallFailure.NO_ANSWER, callFailureFor(480, "TemporarilyUnavailable", peerRang = true))
        assertEquals(CallFailure.UNAVAILABLE, callFailureFor(404, "NotFound", peerRang = false))
    }

    @Test
    fun authenticationAndTransportErrorsMeanCallingIsNotConnected() {
        assertEquals(CallFailure.NOT_CONNECTED, callFailureFor(403, "Forbidden", peerRang = false))
        assertEquals(CallFailure.NOT_CONNECTED, callFailureFor(0, "IOError", peerRang = false))
        assertEquals(CallFailure.NOT_CONNECTED, callFailureFor(0, "NoResponse", peerRang = false))
    }

    @Test
    fun anythingElseIsAGenericFailure() {
        assertEquals(CallFailure.OTHER, callFailureFor(500, "Unknown", peerRang = false))
    }
}
