package com.patbaumgartner.android.stringtelephone.ui

import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

class ChatTimelineTest {
    private fun message(key: String, fromMe: Boolean, at: String) =
        ChatMessage(key, key, fromMe, "", key, Instant.parse(at).toEpochMilli(), SendState.SENT)

    @Test
    fun `day chips separate days and tails mark each change of sender`() {
        val rows = chatTimeline(
            listOf(
                message("a", fromMe = false, at = "2025-06-12T18:02:00Z"),
                message("b", fromMe = false, at = "2025-06-12T18:03:00Z"),
                message("c", fromMe = true, at = "2025-06-12T18:05:00Z"),
                message("d", fromMe = true, at = "2025-06-13T08:00:00Z"),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(
            listOf("day-2025-06-12", "a", "b", "c", "day-2025-06-13", "d"),
            rows.map { it.key },
        )
        val tails = rows.filterIsInstance<ChatRow.Bubble>().associate { it.key to it.startsGroup }
        assertEquals(mapOf("a" to true, "b" to false, "c" to true, "d" to true), tails)
    }

    @Test
    fun `days follow the device time zone`() {
        val late = listOf(message("a", fromMe = false, at = "2025-06-12T23:30:00Z"))

        assertEquals(ChatRow.Day(LocalDate.of(2025, 6, 13)), chatTimeline(late, ZoneId.of("Europe/Zurich")).first())
    }

    @Test
    fun `day labels read like a messenger`() {
        val today = LocalDate.of(2025, 6, 13)
        assertEquals(DayLabel.TODAY, dayLabel(today, today))
        assertEquals(DayLabel.YESTERDAY, dayLabel(today.minusDays(1), today))
        assertEquals(DayLabel.WEEKDAY, dayLabel(today.minusDays(6), today))
        assertEquals(DayLabel.DATE, dayLabel(today.minusDays(7), today))
        assertEquals(DayLabel.DATE, dayLabel(today.plusDays(1), today))
    }

    @Test
    fun `no messages produce no rows`() {
        assertEquals(emptyList(), chatTimeline(emptyList(), ZoneOffset.UTC))
    }
}
