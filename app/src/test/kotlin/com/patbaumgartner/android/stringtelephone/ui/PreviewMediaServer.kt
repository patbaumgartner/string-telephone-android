package com.patbaumgartner.android.stringtelephone.ui

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

/**
 * Test-only localhost HTTP server serving a deterministic preview image at the
 * media URLs the companion API exposes. It proves the URL the image bubble
 * builds is really fetchable with the preview bearer token, and hands the
 * decoded bytes to [RemoteImage]'s preview seam for rendering, because
 * Robolectric cannot reliably dispatch in-compose fetches across a suite.
 */
object PreviewMediaServer {

    const val MEDIA_ID = "preview-media-1"
    private const val TOKEN = "st_preview_token"

    @Volatile private var server: HttpServer? = null
    @Volatile private var base: String = ""

    /** Starts the server once and returns its base URL ("http://127.0.0.1:port"). */
    @Synchronized
    fun baseUrl(): String {
        server?.let { return base }
        val png = renderPreviewPng()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        Runtime.getRuntime().addShutdownHook(Thread { http.stop(0) })
        http.createContext("/") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        http.createContext("/v1/media/$MEDIA_ID") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $TOKEN") {
                    exchange.sendResponseHeaders(401, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, png.size.toLong())
                    exchange.responseBody.use { it.write(png) }
                }
            } finally {
                exchange.close()
            }
        }
        // markRead fires when the chat opens; an empty page keeps it quiet.
        http.createContext("/v1/conversations") { exchange ->
            val body = """{"messageIds":[],"readAt":0}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        http.executor = null // handle exchanges on the dispatcher thread
        http.start()
        server = http
        base = "http://127.0.0.1:${http.address.port}"
        return base
    }

    /** The preview image, decoded once — the seam bitmap [RemoteImage] renders in screenshots. */
    fun previewBitmap(): android.graphics.Bitmap {
        val bytes = renderPreviewPng()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * True when the rendered frame contains the preview's distinct solid
     * colors (sun, mountain) — proof the image painted into the screenshot.
     */
    fun containsPreviewColors(bitmap: android.graphics.Bitmap): Boolean =
        countColor(bitmap, 0xFFFDE68A.toInt()) > 50 &&
            countColor(bitmap, 0xFF155E75.toInt()) > 50

    /**
     * Counts pixels of one exact color in the rendered frame — cheap probe
     * used by the screenshot test to prove the decoded bitmap painted.
     */
    private fun countColor(bitmap: android.graphics.Bitmap, color: Int): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels.count { it == color }
    }

    private fun renderPreviewPng(): ByteArray {
        // Deterministic mountain-scene thumbnail so the committed PNG never drifts.
        val size = 360
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val sky = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            0xFF0E7490.toInt(), 0xFF67E8F9.toInt(), android.graphics.Shader.TileMode.CLAMP,
        ) }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), sky)
        val sun = android.graphics.Paint().apply { color = 0xFFFDE68A.toInt() }
        canvas.drawCircle(size * 0.72f, size * 0.28f, size * 0.11f, sun)
        val mountain = android.graphics.Paint().apply { color = 0xFF155E75.toInt() }
        val snow = android.graphics.Paint().apply { color = 0xFFF8FAFC.toInt() }
        val peak = listOf(0f, 0.55f, 0.25f, 0.15f, 0.55f, 1f)
        val path = android.graphics.Path().apply {
            moveTo(peak[0] * size, peak[1] * size)
            lineTo(peak[2] * size, peak[3] * size)
            lineTo(peak[4] * size, peak[5] * size)
            close()
        }
        canvas.drawPath(path, mountain)
        canvas.drawRect(0f, size * 0.72f, size.toFloat(), size.toFloat(), mountain)
        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
