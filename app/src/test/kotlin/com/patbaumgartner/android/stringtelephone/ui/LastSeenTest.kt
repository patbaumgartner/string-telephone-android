package com.patbaumgartner.android.stringtelephone.ui

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LastSeenTest {

    private val zone = ZoneId.of("UTC")

    /** 2026-10-28 12:00:00 UTC. */
    private val now = 1_793_203_200_000L

    @Test
    fun `today shows the clock-time bucket`() {
        val thisMorning = now - 3 * 60 * 60 * 1000
        assertEquals(LastSeenLabel.TODAY, lastSeenBucket(thisMorning, now, zone))
    }

    @Test
    fun `yesterday and the last week use word buckets`() {
        assertEquals(LastSeenLabel.YESTERDAY, lastSeenBucket(now - 24 * 60 * 60 * 1000, now, zone))
        assertEquals(LastSeenLabel.WEEKDAY, lastSeenBucket(now - 3 * 24 * 60 * 60 * 1000, now, zone))
    }

    @Test
    fun `six days away is a weekday seven days is a date`() {
        assertEquals(LastSeenLabel.WEEKDAY, lastSeenBucket(now - 6L * 24 * 60 * 60 * 1000, now, zone))
        assertEquals(LastSeenLabel.DATE, lastSeenBucket(now - 7L * 24 * 60 * 60 * 1000, now, zone))
    }

    @Test
    fun `far in the past is a plain date`() {
        assertEquals(LastSeenLabel.DATE, lastSeenBucket(now - 400L * 24 * 60 * 60 * 1000, now, zone))
    }

    @Test
    fun `bucket boundaries follow the local calendar day not a fixed window`() {
        // Just before and just after local midnight relative to the fixture's
        // "now" (2026-10-28T16:00Z) — LocalDate.now() here would drift every
        // day the suite runs and fail on the second day, as caught in
        // practice on 2026-10-30.
        val fixtureToday = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val yesterdayMidnight = fixtureToday.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(LastSeenLabel.YESTERDAY, lastSeenBucket(yesterdayMidnight, now, zone))
        val todayMidnight = fixtureToday.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(LastSeenLabel.TODAY, lastSeenBucket(todayMidnight, now, zone))
    }
}
