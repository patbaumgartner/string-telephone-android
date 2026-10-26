package com.patbaumgartner.android.stringtelephone.ui

import com.patbaumgartner.android.stringtelephone.voice.PlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/** Voice-message helpers (Phase 11): duration formatting and waveform parsing. */
class VoiceMessageTest {

    @Test
    fun `formatVoiceDuration renders minutes and seconds`() {
        assertEquals("0:00", formatVoiceDuration(0))
        assertEquals("0:05", formatVoiceDuration(5_000))
        assertEquals("0:59", formatVoiceDuration(59_999))
        assertEquals("1:00", formatVoiceDuration(60_000))
        assertEquals("10:01", formatVoiceDuration(601_000))
    }

    @Test
    fun `normalizedWaveform parses comma-separated amplitudes`() {
        assertEquals(listOf(0, 50, 100), normalizedWaveform("0,50,100"))
        assertEquals(listOf(0, 100), normalizedWaveform(" -3 , 250 "))
        assertEquals(emptyList<Int>(), normalizedWaveform(null))
        assertEquals(emptyList<Int>(), normalizedWaveform(""))
        assertEquals(emptyList<Int>(), normalizedWaveform("x,y,z"))
    }

    @Test
    fun `speed control offers five labelled presets and cycles upward`() {
        val speeds = PlaybackSpeed.entries
        assertEquals(5, speeds.size)
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.5×", "2×"), speeds.map { it.label })
        assertEquals(PlaybackSpeed.X15, speeds[(speeds.indexOf(PlaybackSpeed.X1) + 1) % speeds.size])
        assertEquals(PlaybackSpeed.X05, speeds[(speeds.indexOf(PlaybackSpeed.X2) + 1) % speeds.size])
    }
}
