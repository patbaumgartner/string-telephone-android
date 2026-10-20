package com.patbaumgartner.android.stringtelephone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry rules for the chat scroll thumb: size from the visible-to-total
 * ratio, position from scroll progress, minimum thumb size, and the
 * bottom-pinned behaviour on the last page. The progress input is always a
 * non-negative magnitude, exactly as `ChatScrollbar` computes it from
 * `-firstVisibleItem.offset` (topmost offsets are zero or negative in a
 * forward list).
 */
class ScrollbarMetricsTest {
    private val style = ScrollbarStyle(
        thumbWidthDp = 4f,
        thumbMinHeightDp = 48f,
        edgePaddingDp = 3f,
        densityScale = 1f,
    )

    @Test
    fun `thumb height is the visible-to-total fraction of the viewport`() {
        val metrics = scrollbarMetrics(
            totalItems = 100,
            visibleItems = 10,
            firstVisibleScrolledPx = 0,
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = true,
        )
        assertEquals(100f, metrics.thumbHeightPx)
        assertEquals(0f, metrics.thumbTopPx)
    }

    @Test
    fun `thumb respects the minimum height fraction for long conversations`() {
        // 5 of 1000 items would be 5px tall without the floor.
        val metrics = scrollbarMetrics(
            totalItems = 1000,
            visibleItems = 5,
            firstVisibleScrolledPx = 0,
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = true,
        )
        assertEquals(80f, metrics.thumbHeightPx, 0.01f) // 8% of viewport
    }

    @Test
    fun `thumb respects the absolute minimum height in pixels`() {
        val style = ScrollbarStyle(4f, thumbMinHeightDp = 120f, edgePaddingDp = 3f, densityScale = 2f)
        val metrics = scrollbarMetrics(
            totalItems = 1000,
            visibleItems = 1,
            firstVisibleScrolledPx = 0,
            firstVisibleItemSizePx = 500,
            viewportHeightPx = 1000f,
            canScrollForward = true,
            style = style,
        )
        // 120dp at density 2 = 240px, the larger of the two minimums (viewport floor is 80px).
        assertEquals(240f, metrics.thumbHeightPx, 0.01f)
    }

    @Test
    fun `thumb position follows scroll progress estimated from the first item`() {
        // Scrolled 100px past the top of the first visible item: magnitude 100
        // of a 200px item is halfway, so the thumb sits halfway down its travel.
        val metrics = scrollbarMetrics(
            totalItems = 10,
            visibleItems = 5,
            firstVisibleScrolledPx = 100,
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = true,
        )
        assertEquals(500f, metrics.thumbHeightPx, 0.01f)
        assertEquals(250f, metrics.thumbTopPx, 0.01f) // (1000-500) * 0.5
    }

    @Test
    fun `thumb pins to the bottom when the list cannot scroll forward`() {
        val metrics = scrollbarMetrics(
            totalItems = 20,
            visibleItems = 10,
            firstVisibleScrolledPx = 0, // progress is irrelevant at the end
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = false,
        )
        assertEquals(1f, metrics.thumbTopPx / (1000f - metrics.thumbHeightPx), 0.0001f)
    }

    @Test
    fun `overscroll offsets beyond the first item are clamped to the scrollable range`() {
        val metrics = scrollbarMetrics(
            totalItems = 50,
            visibleItems = 10,
            firstVisibleScrolledPx = 10_000,
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = true,
        )
        // Fraction clamps to 1.0: the thumb parks at the bottom of its travel
        // instead of running past the viewport.
        assertEquals(200f, metrics.thumbHeightPx, 0.01f)
        assertEquals(800f, metrics.thumbTopPx, 0.01f)
    }

    @Test
    fun `a fully visible list fills the viewport`() {
        val metrics = scrollbarMetrics(
            totalItems = 8,
            visibleItems = 8,
            firstVisibleScrolledPx = 0,
            firstVisibleItemSizePx = 125,
            viewportHeightPx = 1000f,
            canScrollForward = false,
        )
        assertEquals(1000f, metrics.thumbHeightPx, 0.01f)
        assertTrue(metrics.fillsViewport)
    }

    @Test
    fun `negative magnitudes cannot push the thumb past the top`() {
        // Guards the fraction coercion against a caller passing a raw
        // topmost-item offset instead of its negation.
        val metrics = scrollbarMetrics(
            totalItems = 10,
            visibleItems = 5,
            firstVisibleScrolledPx = -50,
            firstVisibleItemSizePx = 200,
            viewportHeightPx = 1000f,
            canScrollForward = true,
        )
        assertEquals(0f, metrics.thumbTopPx)
    }

    @Test
    fun `impossible inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            scrollbarMetrics(0, 1, 0, 100, 1000f, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scrollbarMetrics(10, 1, 0, 0, 1000f, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scrollbarMetrics(10, 1, 0, 200, 0f, true)
        }
    }
}
