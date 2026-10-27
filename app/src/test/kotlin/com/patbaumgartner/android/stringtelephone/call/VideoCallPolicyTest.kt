package com.patbaumgartner.android.stringtelephone.call

import com.patbaumgartner.android.stringtelephone.core.CallState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Video policy is framework-free on purpose: the rules below decide whether a
 * camera turns on at all, which is exactly the part that must not depend on
 * liblinphone or Android to be verified (ADR-0013).
 */
class VideoCallPolicyTest {

    @Test
    fun `video starts only when the caller asked and the camera is allowed`() {
        assertTrue(startWithVideo(requested = true, cameraAllowed = true))
        assertFalse(startWithVideo(requested = true, cameraAllowed = false))
        assertFalse(startWithVideo(requested = false, cameraAllowed = true))
        assertFalse(startWithVideo(requested = false, cameraAllowed = false))
    }

    @Test
    fun `leaving the call screen removes every video flag and keeps the call state`() {
        val running = CallSessionInfo(
            remoteUser = "bob",
            state = CallState.ACTIVE,
            videoOffered = true,
            videoEnabled = true,
            videoActive = true,
        )

        val hidden = afterCallScreenHidden(running)

        assertEquals("bob", hidden.remoteUser)
        assertEquals(CallState.ACTIVE, hidden.state)
        assertFalse(hidden.videoOffered)
        assertFalse(hidden.videoEnabled)
        assertFalse(hidden.videoActive)
    }

    @Test
    fun `an audio-only call is returned unchanged when the screen hides`() {
        val audio = CallSessionInfo("bob", CallState.ACTIVE)
        assertSame(audio, afterCallScreenHidden(audio))
    }

    @Test
    fun `camera flip cycles through the available devices and wraps`() {
        val both = listOf(CameraFacing.FRONT, CameraFacing.BACK)
        assertEquals(CameraFacing.BACK, nextCameraFacing(CameraFacing.FRONT, both))
        assertEquals(CameraFacing.FRONT, nextCameraFacing(CameraFacing.BACK, both))
        assertEquals(CameraFacing.BACK, nextCameraFacing(CameraFacing.FRONT, listOf(CameraFacing.BACK)))
        assertEquals(CameraFacing.FRONT, nextCameraFacing(CameraFacing.FRONT, emptyList()))
    }

    @Test
    fun `video calls prefer speaker and audio calls stay on the earpiece`() {
        val all = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        assertEquals(AudioRoute.SPEAKER, defaultAudioRoute(all, withVideo = true))
        assertEquals(AudioRoute.EARPIECE, defaultAudioRoute(all, withVideo = false))
    }

    @Test
    fun `a connected headset wins over the video speaker default`() {
        val withHeadset = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER, AudioRoute.BLUETOOTH)
        assertEquals(AudioRoute.BLUETOOTH, defaultAudioRoute(withHeadset, withVideo = true))
        assertEquals(AudioRoute.BLUETOOTH, defaultAudioRoute(withHeadset, withVideo = false))
    }

    @Test
    fun `a device without a speaker still gets a usable route`() {
        assertEquals(AudioRoute.EARPIECE, defaultAudioRoute(setOf(AudioRoute.EARPIECE), withVideo = true))
        assertEquals(AudioRoute.BLUETOOTH, defaultAudioRoute(setOf(AudioRoute.BLUETOOTH), withVideo = false))
        assertEquals(AudioRoute.EARPIECE, defaultAudioRoute(emptySet(), withVideo = true))
    }
}
