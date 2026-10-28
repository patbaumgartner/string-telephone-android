package com.patbaumgartner.android.stringtelephone.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a last-seen timestamp reads to a human (Phase 13). */
internal enum class LastSeenLabel { TODAY, YESTERDAY, WEEKDAY, DATE }

/**
 * Buckets a last-seen instant the way the chat's day chips read dates:
 * today shows the clock time, the last week a weekday, anything older a
 * date. Framework-free so unit tests cover the boundaries. [lastSeenEpochMs]
 * is never in the future (the server truncates to the minute), but a skewed
 * clock degrades to TODAY rather than nothing.
 */
internal fun lastSeenBucket(lastSeenEpochMs: Long, nowEpochMs: Long, zone: ZoneId): LastSeenLabel {
    val seen = Instant.ofEpochMilli(lastSeenEpochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    return when {
        seen == today -> LastSeenLabel.TODAY
        seen == today.minusDays(1) -> LastSeenLabel.YESTERDAY
        seen.isAfter(today.minusDays(7)) && seen.isBefore(today) -> LastSeenLabel.WEEKDAY
        else -> LastSeenLabel.DATE
    }
}
