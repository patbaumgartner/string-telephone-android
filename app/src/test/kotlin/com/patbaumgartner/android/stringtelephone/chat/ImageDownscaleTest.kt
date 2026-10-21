package com.patbaumgartner.android.stringtelephone.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Client-side downscale before upload (Phase 6): at most 1920 px on the long
 * edge, JPEG quality 85. Robolectric provides real graphics native bindings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageDownscaleTest {

    private fun jpegOf(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(0, 121, 163))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray()
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bitmap, "output must still decode as an image")
        return bitmap.width to bitmap.height
    }

    @Test
    fun `a large image is downscaled to the 1920 pixel cap`() {
        val (w, h) = dimensions(ImageDownscale.downscale(jpegOf(4000, 3000)))
        assertEquals(1920, maxOf(w, h))
    }

    @Test
    fun `a small image keeps its dimensions`() {
        val (w, h) = dimensions(ImageDownscale.downscale(jpegOf(800, 600)))
        assertEquals(800 to 600, w to h)
    }

    @Test
    fun `aspect ratio survives the downscale`() {
        val (w, h) = dimensions(ImageDownscale.downscale(jpegOf(4000, 1000)))
        assertEquals(1920, maxOf(w, h))
        assertEquals(0.25, h.toDouble() / w.toDouble(), 0.02)
    }

    @Test
    fun `the re-encoded payload is smaller than the camera capture`() {
        val original = jpegOf(6000, 4000)
        val scaled = ImageDownscale.downscale(original)
        assertTrue(scaled.size < original.size, "JPEG 85 re-encode of a huge image must shrink the payload")
        val (w, h) = dimensions(scaled)
        assertEquals(1920, maxOf(w, h))
    }
}
