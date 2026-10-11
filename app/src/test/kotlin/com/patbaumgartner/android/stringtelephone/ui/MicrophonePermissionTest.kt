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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MicrophonePermissionTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private var calls = 0
    private var denied = false

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    private fun showCallButton() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val requestMicrophone = rememberMicrophonePermission { denied = true }
            StringTelephoneTheme {
                Button(onClick = { requestMicrophone { calls++ } }) { Text("Call") }
            }
        }
    }

    @Test
    fun `already granted microphone permission starts the call immediately`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        showCallButton()
        compose.onNodeWithText("Call").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        assertFalse(denied)
    }

    @Test
    fun `first call waits for microphone permission and continues exactly once on grant`() {
        showCallButton()
        compose.onNodeWithText("Call").performClick()
        val request = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        assertContentEquals(arrayOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
        assertEquals(0, calls)
        compose.runOnIdle {
            controller.get().onRequestPermissionsResult(
                request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
        }
        compose.runOnIdle { assertEquals(1, calls) }
        assertFalse(denied)
    }

    @Test
    fun `denied permission does not start a call and asks the UI to explain recovery`() {
        showCallButton()
        compose.onNodeWithText("Call").performClick()
        val request = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
        compose.runOnIdle {
            controller.get().onRequestPermissionsResult(
                request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED),
            )
        }
        compose.runOnIdle {
            assertEquals(0, calls)
            assertTrue(denied)
        }
    }
}
