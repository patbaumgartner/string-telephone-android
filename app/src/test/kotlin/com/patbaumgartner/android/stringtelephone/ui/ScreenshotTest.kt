package com.patbaumgartner.android.stringtelephone.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders each screen through [ScreenshotActivity] with Robolectric native
 * graphics and writes the result as a PNG under build/screenshots, so design
 * changes can be reviewed as images without a device or emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi", application = ScreenshotTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    private val zone = java.util.TimeZone.getDefault()
    private val locale = java.util.Locale.getDefault()

    @org.junit.Before
    fun fixedClock() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        java.util.Locale.setDefault(java.util.Locale.US)
    }

    @org.junit.After
    fun restoreClock() {
        java.util.TimeZone.setDefault(zone)
        java.util.Locale.setDefault(locale)
    }

    @Test
    fun captureOnboarding() {
        capture("01-onboarding", ScreenshotRoute.ONBOARDING)
    }

    @Test
    fun captureContacts() {
        capture("02-contacts", ScreenshotRoute.CONTACTS)
    }

    @Test
    fun captureChat() {
        capture("03-chat", ScreenshotRoute.CHAT)
    }

    @Test
    fun captureChatScrollbar() {
        capture("09-chat-scrollbar", ScreenshotRoute.CHAT_SCROLLBAR)
    }

    /** Every delivery state the chat can draw, one bubble each (ADR-0010). */
    @Test
    fun captureChatDeliveryStates() {
        capture("10-chat-delivery-states", ScreenshotRoute.CHAT_DELIVERY_STATES)
    }

    /** The image-message bubble with its real thumbnail over local HTTP (Phase 6). */
    @Test
    fun captureChatImage() {
        capture("11-chat-image", ScreenshotRoute.CHAT_IMAGE, waitForImage = true)
    }

    /** Dark variant of the image bubble (Phase 6), also over real HTTP. */
    @org.junit.Test
    fun captureDarkChatImage() {
        capture("dark-chat-image", ScreenshotRoute.CHAT_IMAGE, dark = true, waitForImage = true)
    }

    /** Reaction chips under text bubbles (Phase 8, ADR-0011). */
    @Test
    fun captureChatReactions() {
        capture("12-chat-reactions", ScreenshotRoute.CHAT_REACTIONS)
    }

    /** A reply quoting a received message (Phase 9, ADR-0011). */
    @Test
    fun captureChatReply() {
        capture("13-chat-reply", ScreenshotRoute.CHAT_REPLY)
    }

    /** Tombstone placeholder among live messages (Phase 10, ADR-0011). */
    @Test
    fun captureChatDeletion() {
        capture("14-chat-deletion", ScreenshotRoute.CHAT_DELETION)
    }

    /** Voice-message bubble with waveform, duration and speed toggle (Phase 11). */
    @Test
    fun captureChatVoice() {
        capture("15-chat-voice", ScreenshotRoute.CHAT_VOICE)
    }

    @Test
    fun captureCall() {
        capture("04-call", ScreenshotRoute.CALL)
    }

    /** Full-screen remote video with the self-view PiP and camera controls (Phase 12). */
    @Test
    fun captureVideoCall() {
        capture("16-call-video", ScreenshotRoute.CALL_VIDEO)
    }

    @Test
    fun captureSettings() {
        capture("05-settings", ScreenshotRoute.SETTINGS)
    }

    @Test
    fun captureDarkScreens() {
        ScreenshotRoute.entries.filter { it != ScreenshotRoute.CHAT_IMAGE }.forEach { route ->
            capture("dark-${route.name.lowercase().replace('_', '-')}", route, dark = true)
        }
    }

    @Test
    fun captureSignInError() {
        capture("06-sign-in-error", ScreenshotRoute.ONBOARDING, error = true)
    }

    @Test
    fun captureIncomingCallAccess() {
        capture("08-incoming-call-access", ScreenshotRoute.CONTACTS, error = true)
    }

    @Test
    fun captureLauncher() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = requireNotNull(context.getDrawable(R.mipmap.ic_launcher))
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(android.graphics.Canvas(bitmap))
        writePng("07-launcher", bitmap)
    }

    private fun capture(name: String, route: ScreenshotRoute, dark: Boolean = false, error: Boolean = false, waitForImage: Boolean = false) {
        val intent = Intent(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            ScreenshotActivity::class.java,
        ).putExtra(ScreenshotActivity.EXTRA_ROUTE, route.ordinal)
            .putExtra(ScreenshotActivity.EXTRA_DARK, dark)
            .putExtra(ScreenshotActivity.EXTRA_ERROR, error)
        // Robolectric dispatches a later activity's compose effects on a stale
        // looper, so an in-compose fetch is non-deterministic across a suite.
        // The seam keeps the screenshot deterministic: RemoteImage renders the
        // pre-decoded preview bitmap (same paint path as a fetched one), while
        // PreviewMediaServer proves the built media URL is really fetchable.
        // Set before the activity starts so the first composition includes it.
        if (waitForImage) RemoteImagePreview = PreviewMediaServer.previewBitmap()
        val controller = org.robolectric.Robolectric.buildActivity(ScreenshotActivity::class.java, intent)
        val activity = controller.setup().get()
        val decor = activity.window.decorView
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2280, android.view.View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 2280)
        val bitmap = Bitmap.createBitmap(1080, 2280, Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(bitmap))
        if (waitForImage) {
            RemoteImagePreview = null
            // Prove the media URL the bubble builds is really fetchable with
            // the preview token: same path, same auth the app would send.
            val media = java.net.URL(
                "${PreviewMediaServer.baseUrl()}/v1/media/${PreviewMediaServer.MEDIA_ID}/thumbnail",
            ).openConnection() as java.net.HttpURLConnection
            media.setRequestProperty("Authorization", "Bearer st_preview_token")
            check(media.responseCode == 200) { "preview media URL not fetchable: ${media.responseCode}" }
            media.inputStream.use { check(it.readBytes().isNotEmpty()) }
            media.disconnect()
            check(PreviewMediaServer.containsPreviewColors(bitmap)) {
                "preview image rendered but its colors are missing from the frame"
            }
        }
        writePng(name, bitmap)
        controller.pause().stop().destroy()
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
