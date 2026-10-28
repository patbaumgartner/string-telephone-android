package com.patbaumgartner.android.stringtelephone.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which relative shape a conversation-row timestamp takes (Phase 15). */
internal enum class PreviewTimeLabel { TODAY, YESTERDAY, WEEKDAY, DATE }

/**
 * Buckets a conversation's newest message the way the chat's day chips read
 * dates: today shows the clock time, the last week a weekday, anything older
 * a date. Reuses [dayLabel]'s semantics; framework-free so unit tests cover
 * the boundaries. A future timestamp (skewed clock) degrades to TODAY.
 */
internal fun previewTimeBucket(epochMs: Long, nowEpochMs: Long, zone: ZoneId): PreviewTimeLabel {
    val seen = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    return when {
        seen == today -> PreviewTimeLabel.TODAY
        seen == today.minusDays(1) -> PreviewTimeLabel.YESTERDAY
        seen.isAfter(today.minusDays(7)) && seen.isBefore(today) -> PreviewTimeLabel.WEEKDAY
        else -> PreviewTimeLabel.DATE
    }
}

/** Which message kinds need a placeholder instead of raw content (Phase 15). */
internal fun isPlaceholderPreview(message: com.patbaumgartner.android.stringtelephone.chat.ChatMessage): Boolean =
    message.deleted || message.type == "IMAGE" || message.type == "VOICE"
