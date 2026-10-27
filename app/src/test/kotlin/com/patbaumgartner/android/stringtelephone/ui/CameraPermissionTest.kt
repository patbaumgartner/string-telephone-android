package com.patbaumgartner.android.stringtelephone.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The camera gate mirrors the microphone gate, but a denial must never block
 * the call: the video path falls back to audio instead of aborting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraPermissionTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private var granted = 0
    private var denied = 0

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun showVideoButton() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val requestCamera = rememberCameraPermission()
            StringTelephoneTheme {
                Button(onClick = { requestCamera({ granted++ }, { denied++ }) }) { Text("Video call") }
            }
        }
    }

    @Test
    fun `already granted camera permission starts video immediately`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
        showVideoButton()
        compose.onNodeWithText("Video call").performClick()
        compose.runOnIdle { assertEquals(1, granted) }
        assertEquals(0, denied)
    }

    @Test
    fun `first video call waits for the camera permission and continues once on grant`() {
        showVideoButton()
        compose.onNodeWithText("Video call").performClick()
        val request = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        assertContentEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
        assertEquals(0, granted)
        compose.runOnIdle {
            controller.get().onRequestPermissionsResult(
                request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
        }
        compose.runOnIdle { assertEquals(1, granted) }
        assertEquals(0, denied)
    }

    @Test
    fun `denied camera permission falls back to audio exactly once`() {
        showVideoButton()
        compose.onNodeWithText("Video call").performClick()
        val request = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        compose.runOnIdle {
            controller.get().onRequestPermissionsResult(
                request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.runOnIdle {
            assertEquals(0, granted)
            assertEquals(1, denied)
        }
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            androidx.core.content.ContextCompat.checkSelfPermission(
                ApplicationProvider.getApplicationContext(),
                Manifest.permission.CAMERA,
            ),
        )
    }
}
