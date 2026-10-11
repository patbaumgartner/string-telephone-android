package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-160dpi", application = ScreenshotTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun `secret fields accept long hyphenated passphrases without truncation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vm = AppViewModel(
            AppContainer(context, FakeCredentialStore(), FakeCallEngine(), StringTelephoneRepository({ null }, ChatCache())),
        )
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            StringTelephoneTheme { OnboardingScreen(vm) }
        }
        val passphrase = "fixture-robin-maple-meadow-lantern-pebble-willow-scarlet-1234"
        compose.onNodeWithText(context.getString(R.string.onboarding_password))
            .performScrollTo().performTextReplacement(passphrase)
        compose.onNodeWithText(context.getString(R.string.onboarding_sip_secret))
            .performScrollTo().performTextReplacement(passphrase)
        compose.runOnIdle {
            assertEquals(passphrase, vm.passwordInput)
            assertEquals(passphrase, vm.sipSecretInput)
        }
    }

    @Test
    fun `error sign in and administrator guidance are reachable on a small screen with large text`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = StringTelephoneRepository({ null }, ChatCache())
        val vm = AppViewModel(
            AppContainer(context, FakeCredentialStore(), FakeCallEngine(), repository),
        )
        repository.reportSignInError(SignInError.AUTHENTICATION)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                StringTelephoneTheme {
                    Surface(color = MaterialTheme.colorScheme.background) { OnboardingScreen(vm) }
                }
            }
        }
        listOf(R.string.sign_in_authentication, R.string.onboarding_sign_in, R.string.onboarding_credentials_note)
            .forEach { resource ->
                compose.onNodeWithText(context.getString(resource)).performScrollTo().assertIsDisplayed()
            }
    }
}
