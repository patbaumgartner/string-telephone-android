package com.patbaumgartner.android.stringtelephone.ui

import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the conversation list: a day chip or a message bubble. */
internal sealed interface ChatRow {
    val key: String

    data class Day(val date: LocalDate) : ChatRow {
        override val key = "day-$date"
    }

    /** [startsGroup] marks the first of consecutive messages from one sender; only it gets a tail. */
    data class Bubble(val message: ChatMessage, val startsGroup: Boolean) : ChatRow {
        override val key = message.clientKey
    }
}

/** Interleaves day chips and marks sender groups, as messengers show conversations. */
internal fun chatTimeline(messages: List<ChatMessage>, zone: ZoneId): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var previousDay: LocalDate? = null
    var previousFromMe: Boolean? = null
    for (message in messages) {
        val day = Instant.ofEpochMilli(message.sentAtEpochMs).atZone(zone).toLocalDate()
        val newDay = day != previousDay
        if (newDay) rows += ChatRow.Day(day)
        rows += ChatRow.Bubble(message, startsGroup = newDay || message.fromMe != previousFromMe)
        previousDay = day
        previousFromMe = message.fromMe
    }
    return rows
}

/**
 * The timeline row index of a message (Phase 16): search results reference a
 * message id; the LazyColumn needs its [ChatRow.Bubble] index to scroll
 * there. Returns null when the message is not in the loaded timeline (not
 * cached — history pagination may not have reached it).
 */
internal fun timelineIndexOf(rows: List<ChatRow>, clientKey: String): Int =
    rows.indexOfFirst { it is ChatRow.Bubble && it.message.clientKey == clientKey }

internal enum class DayLabel { TODAY, YESTERDAY, WEEKDAY, DATE }

/** Recent days read as words, the last week as weekdays, anything older as a date. */
internal fun dayLabel(date: LocalDate, today: LocalDate): DayLabel = when {
    date == today -> DayLabel.TODAY
    date == today.minusDays(1) -> DayLabel.YESTERDAY
    date.isAfter(today.minusDays(7)) && date.isBefore(today) -> DayLabel.WEEKDAY
    else -> DayLabel.DATE
}
