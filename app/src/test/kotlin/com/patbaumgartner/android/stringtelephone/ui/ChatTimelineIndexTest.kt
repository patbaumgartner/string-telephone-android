package com.patbaumgartner.android.stringtelephone.ui

import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scroll-to-result (Phase 16): a search result references a message; the
 * LazyColumn needs its bubble-row index. The finder is pure and unit-tested;
 * the jump itself is `LazyListState.animateScrollToItem`.
 */
class ChatTimelineIndexTest {

    private val zone = ZoneId.of("UTC")

    private fun msg(key: String, at: Long) = ChatMessage(
        serverId = "srv-$key",
        clientKey = key,
        fromMe = false,
        content = "text $key",
        sentAtEpochMs = at,
        state = SendState.SENT,
    )

    @Test
    fun `the finder locates a bubble row by client key`() {
        val messages = listOf(msg("a", 1_000), msg("b", 2_000), msg("c", 3_000))
        val rows = chatTimeline(messages, zone)

        // One day chip at index 0, then one row per bubble.
        assertEquals(1, timelineIndexOf(rows, "a"))
        assertEquals(2, timelineIndexOf(rows, "b"))
        assertEquals(3, timelineIndexOf(rows, "c"))
    }

    @Test
    fun `a message missing from the timeline maps to no index`() {
        val rows = chatTimeline(listOf(msg("a", 1_000)), zone)
        assertEquals(-1, timelineIndexOf(rows, "not-cached"))
    }

    @Test
    fun `day chips count as rows so the index is the LazyColumn index`() {
        // Two calendar days -> two day chips; the finder's index is the row index.
        val day2 = 26 * 60 * 60 * 1000L // next UTC day
        val messages = listOf(msg("a", 1_000), msg("b", day2))
        val rows = chatTimeline(messages, zone)
        assertEquals(2, rows.count { it is ChatRow.Day }, "fixture must span two days")
        assertEquals(rows.lastIndex, timelineIndexOf(rows, "b"))
    }
}
