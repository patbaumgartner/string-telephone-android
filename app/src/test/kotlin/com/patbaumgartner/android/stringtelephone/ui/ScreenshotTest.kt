package com.patbaumgartner.android.stringtelephone.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
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
    fun captureCall() {
        capture("04-call", ScreenshotRoute.CALL)
    }

    @Test
    fun captureSettings() {
        capture("05-settings", ScreenshotRoute.SETTINGS)
    }

    private fun capture(name: String, route: ScreenshotRoute) {
        val intent = Intent(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            ScreenshotActivity::class.java,
        ).putExtra(ScreenshotActivity.EXTRA_ROUTE, route.ordinal)
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
        writePng(name, bitmap)
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
