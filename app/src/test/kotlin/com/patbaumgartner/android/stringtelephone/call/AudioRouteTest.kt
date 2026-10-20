package com.patbaumgartner.android.stringtelephone.call

import kotlin.test.Test
import kotlin.test.assertEquals

/** The route rules that run when the output-device list changes mid-call. */
class AudioRouteTest {

    private val base = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
    private val withBluetooth = base + AudioRoute.BLUETOOTH

    @Test
    fun keepsTheEarpieceSelectionWhileTheHeadsetStaysAvailable() {
        // Earpiece selected with a headset present must not be taken over.
        assertEquals(
            AudioRoute.EARPIECE,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.EARPIECE, available = withBluetooth),
        )
    }

    @Test
    fun fallsBackToTheEarpieceWhenTheHeadsetDisconnects() {
        assertEquals(
            AudioRoute.EARPIECE,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.BLUETOOTH, available = base),
        )
    }

    @Test
    fun takesOverWhenAHeadsetConnectsMidCall() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            resolveAudioRoute(previousAvailable = base, current = AudioRoute.SPEAKER, available = withBluetooth),
        )
    }

    @Test
    fun switchesFromTheEarpieceToANewlyConnectedHeadset() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            resolveAudioRoute(previousAvailable = base, current = AudioRoute.EARPIECE, available = withBluetooth),
        )
    }

    @Test
    fun keepsTheUsersSpeakerChoiceWhenTheHeadsetWasAlreadyConnected() {
        assertEquals(
            AudioRoute.SPEAKER,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.SPEAKER, available = withBluetooth),
        )
    }

    @Test
    fun keepsTheHeadsetWhileItStaysAvailable() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.BLUETOOTH, available = withBluetooth),
        )
    }

    @Test
    fun picksTheSoleRemainingRouteWhenTheEarpieceIsGone() {
        assertEquals(
            AudioRoute.SPEAKER,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.BLUETOOTH, available = setOf(AudioRoute.SPEAKER)),
        )
    }

    @Test
    fun keepsTheCurrentRouteWhileNoOutputDevicesAreListed() {
        assertEquals(
            AudioRoute.BLUETOOTH,
            resolveAudioRoute(previousAvailable = withBluetooth, current = AudioRoute.BLUETOOTH, available = emptySet()),
        )
    }

    @Test
    fun doesNotTakeBackAudioAfterAFlapWhenTheUserPinnedTheSpeaker() {
        // The headset list flapped away and returned; the user had explicitly
        // chosen the speaker in between, so no takeover may fire.
        assertEquals(
            AudioRoute.SPEAKER,
            resolveAudioRoute(
                previousAvailable = base,
                current = AudioRoute.SPEAKER,
                available = withBluetooth,
                userPinnedRoute = AudioRoute.SPEAKER,
            ),
        )
    }

    @Test
    fun prefersTheSpeakerWhenOnlyExternalOutputsRemain() {
        // Independent of the order liblinphone enumerated its devices in.
        assertEquals(
            AudioRoute.SPEAKER,
            resolveAudioRoute(
                previousAvailable = withBluetooth,
                current = AudioRoute.EARPIECE,
                available = setOf(AudioRoute.BLUETOOTH, AudioRoute.SPEAKER),
            ),
        )
    }
}
