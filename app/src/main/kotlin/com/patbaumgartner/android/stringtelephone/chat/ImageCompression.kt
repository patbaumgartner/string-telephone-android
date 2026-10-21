package com.patbaumgartner.android.stringtelephone.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Client-side downscale before upload (Phase 6): at most 1920 px on the long
 * edge, JPEG quality 85. A smaller image is re-encoded but never enlarged, so
 * uploads stay bounded whatever the camera produced.
 */
object ImageDownscale {

    /** Decodes, downscales to at most [maxDim] on the longer edge and re-encodes as JPEG. */
    fun downscale(bytes: ByteArray, maxDim: Int = 1920, quality: Int = 85): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val scale = minOf(
            maxDim.toDouble() / bitmap.width.toDouble(),
            maxDim.toDouble() / bitmap.height.toDouble(),
            1.0,
        )
        val out = java.io.ByteArrayOutputStream()
        if (scale >= 1.0) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
