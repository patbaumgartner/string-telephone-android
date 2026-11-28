package com.patbaumgartner.android.stringtelephone.ui

import android.Manifest
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.stringTelephoneContainer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real-activity validation; JVM SignInTest proves rejected input causes no request or saved session. */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {

    @get:Rule
    val permission = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun str(res: Int) = compose.activity.getString(res)

    @Before
    fun clearSession() {
        compose.activity.stringTelephoneContainer.repository.clearSession()
        compose.waitForIdle()
    }

    @Test
    fun onboardingRequiresApiTokenBeforeSignIn() {
        assertMissingCredentialsWhen(R.string.onboarding_password)
    }

    @Test
    fun onboardingRequiresSipPasswordBeforeSignIn() {
        assertMissingCredentialsWhen(R.string.onboarding_sip_secret)
    }

    private fun assertMissingCredentialsWhen(missingField: Int) {
        compose.onNodeWithText(str(R.string.onboarding_tagline)).assertIsDisplayed()
        val values = mapOf(
            R.string.onboarding_server_url to "https://example.invalid",
            R.string.onboarding_username to "alice",
            R.string.onboarding_password to "test-api-token",
            R.string.onboarding_sip_secret to "test-sip-password",
        )
        values.forEach { (field, value) ->
            compose.onNodeWithText(str(field))
                .performTextReplacement(if (field == missingField) "" else value)
        }
        compose.onNodeWithText(str(R.string.sign_in_missing_credentials)).assertDoesNotExist()

        compose.onNodeWithText(str(R.string.onboarding_sign_in))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.onNodeWithText(str(R.string.sign_in_missing_credentials))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
