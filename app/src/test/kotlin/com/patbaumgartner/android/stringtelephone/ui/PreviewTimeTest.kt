package com.patbaumgartner.android.stringtelephone.ui

import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Conversation-row preview rules (Phase 15): relative timestamp buckets
 * match the chat's day chips, and media/deleted messages take placeholders
 * instead of raw content (ADR-0011).
 */
class PreviewTimeTest {

    private val zone = ZoneId.of("UTC")

    /** 2026-10-28 16:00:00 UTC (a Wednesday). */
    private val now = 1_793_203_200_000L

    @Test
    fun `today shows the clock-time bucket`() {
        val thisMorning = now - 3 * 60 * 60 * 1000
        assertEquals(PreviewTimeLabel.TODAY, previewTimeBucket(thisMorning, now, zone))
    }

    @Test
    fun `yesterday uses the word bucket`() {
        val yesterday = now - 24 * 60 * 60 * 1000
        assertEquals(PreviewTimeLabel.YESTERDAY, previewTimeBucket(yesterday, now, zone))
    }

    @Test
    fun `six days away is a weekday seven days is a date`() {
        val sixDays = now - 6 * 24 * 60 * 60 * 1000
        val sevenDays = now - 7 * 24 * 60 * 60 * 1000
        assertEquals(PreviewTimeLabel.WEEKDAY, previewTimeBucket(sixDays, now, zone))
        assertEquals(PreviewTimeLabel.DATE, previewTimeBucket(sevenDays, now, zone))
    }

    @Test
    fun `far in the past is a plain date`() {
        assertEquals(PreviewTimeLabel.DATE, previewTimeBucket(now - 40L * 24 * 60 * 60 * 1000, now, zone))
    }

    @Test
    fun `a future timestamp degrades to today rather than nothing`() {
        assertEquals(PreviewTimeLabel.TODAY, previewTimeBucket(now + 60_000, now, zone))
    }

    @Test
    fun `bucket boundaries follow the local calendar day not a fixed window`() {
        // now is 2026-10-28 16:00 UTC: a millisecond before local midnight is
        // yesterday, midnight itself is today — not fixed 24h windows.
        val yesterday2359 = 1_793_145_599_999L
        val today0000 = 1_793_145_600_000L
        assertEquals(PreviewTimeLabel.YESTERDAY, previewTimeBucket(yesterday2359, now, zone))
        assertEquals(PreviewTimeLabel.TODAY, previewTimeBucket(today0000, now, zone))
    }

    @Test
    fun `tombstones and media take placeholders text does not`() {
        val text = ChatMessage("s1", "k1", fromMe = false, "plain text", 1_000, SendState.SENT)
        assertFalse(isPlaceholderPreview(text))

        val image = ChatMessage("s2", "k2", fromMe = false, "", 1_000, SendState.SENT, type = "IMAGE")
        assertTrue(isPlaceholderPreview(image))

        val voice = ChatMessage("s3", "k3", fromMe = false, "", 1_000, SendState.SENT, type = "VOICE")
        assertTrue(isPlaceholderPreview(voice))

        val tombstone = ChatMessage("s4", "k4", fromMe = false, "gone", 1_000, SendState.SENT, deletedAtEpochMs = 2_000)
        assertTrue(isPlaceholderPreview(tombstone))
    }
}
