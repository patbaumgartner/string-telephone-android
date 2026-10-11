package com.patbaumgartner.android.stringtelephone.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.ScreenshotTestApp
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.call.CallEvent
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ScreenshotTestApp::class)
class SignInTest {
    private val store = FakeCredentialStore()
    private val engine = FakeCallEngine()
    private lateinit var repository: StringTelephoneRepository

    private fun viewModel(
        expectedToken: String = "test-api-token",
        response: () -> Pair<Int, String>,
    ): AppViewModel {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer $expectedToken", chain.request().header("Authorization"))
            val (code, body) = if (chain.request().url.encodedPath == "/v1/me") {
                response()
            } else {
                200 to """{"contacts":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test response").body(body.toResponseBody()).build()
        }.build()
        repository = StringTelephoneRepository(
            { CompanionApiClient("https://phone.example.com", { requireNotNull(store.apiToken()) }, http) },
            ChatCache(),
        )
        return AppViewModel(
            AppContainer(
                ApplicationProvider.getApplicationContext(),
                credentialStoreOverride = store,
                callEngineOverride = engine,
                repositoryOverride = repository,
            ),
        ).apply {
            onServerUrlChanged("https://phone.example.com")
            onUsernameChanged("alice")
            onPasswordChanged("test-api-token")
            onSipSecretChanged("separate-sip-password")
        }
    }

    @After
    fun tearDown() {
        repository.clearSession()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `long hyphenated credentials reach API storage and SIP unchanged`() {
        val passphrase = "fixture-robin-maple-meadow-lantern-pebble-willow-scarlet-1234"
        val apiToken = "$passphrase-api"
        val sipPassword = "$passphrase-sip"
        val vm = viewModel(expectedToken = apiToken) {
            200 to """{"username":"alice","displayName":"Alice"}"""
        }
        vm.onPasswordChanged(apiToken)
        vm.onSipSecretChanged(sipPassword)
        vm.signIn()
        await { vm.screen.value == AppScreen.Contacts }
        assertEquals(apiToken, vm.passwordInput)
        assertEquals(apiToken, store.apiToken())
        assertEquals(sipPassword, vm.sipSecretInput)
        assertEquals(sipPassword, store.sipSecret())
        assertEquals(sipPassword, engine.startedConfig?.sipSecret)
    }

    @Test
    fun `failed authentication stays on sign in without starting SIP or retaining credentials`() {
        val vm = viewModel { 401 to """{"error":"unauthorized"}""" }
        vm.signIn()
        await { repository.signIn.value.error == SignInError.AUTHENTICATION && !store.hasCredentials() }
        assertEquals(AppScreen.Onboarding, vm.screen.value)
        assertNull(engine.startedConfig)
        assertFalse(repository.signIn.value.success)
        assertFalse(repository.signIn.value.inProgress)
    }

    @Test
    fun `only verified credentials open contacts and SIP uses its own password`() {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val vm = viewModel {
            requested.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            200 to """{"username":"alice","displayName":"Alice"}"""
        }
        try {
            vm.signIn()
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            assertTrue(repository.signIn.value.inProgress)
            assertEquals(AppScreen.Onboarding, vm.screen.value)
            assertNull(engine.startedConfig)
        } finally {
            release.countDown()
        }
        await { vm.screen.value == AppScreen.Contacts }
        assertEquals("test-api-token", store.apiToken())
        assertEquals("separate-sip-password", engine.startedConfig?.sipSecret)
        assertEquals("alice", engine.startedConfig?.username)
        vm.clearLocalData()
        assertFalse(store.hasCredentials())
        assertNull(engine.startedConfig)
        assertEquals(AppScreen.Onboarding, vm.screen.value)
    }

    @Test
    fun `network failure is visible and retry succeeds`() {
        var offline = true
        val vm = viewModel {
            if (offline) throw IOException("offline")
            200 to """{"username":"alice","displayName":"Alice"}"""
        }
        vm.signIn()
        await { repository.signIn.value.error == SignInError.NETWORK && !store.hasCredentials() }
        assertEquals(AppScreen.Onboarding, vm.screen.value)
        offline = false
        vm.signIn()
        await { vm.screen.value == AppScreen.Contacts }
        assertTrue(repository.signIn.value.success)
    }

    @Test
    fun `API token for a different username cannot start the SIP account`() {
        val vm = viewModel { 200 to """{"username":"bob","displayName":"Bob"}""" }
        vm.signIn()
        await { repository.signIn.value.error == SignInError.IDENTITY && !store.hasCredentials() }
        assertNull(engine.startedConfig)
        assertEquals(AppScreen.Onboarding, vm.screen.value)
    }

    @Test
    fun `invalid server response is visible instead of leaving sign in spinning`() {
        val vm = viewModel { 200 to "<html>not the API</html>" }
        vm.signIn()
        await { repository.signIn.value.error == SignInError.PROTOCOL && !store.hasCredentials() }
        assertFalse(repository.signIn.value.inProgress)
    }

    @Test
    fun `missing SIP password reports validation error before saving credentials`() {
        val vm = viewModel { error("No request should be made") }
        vm.onSipSecretChanged("")
        vm.signIn()
        assertEquals(SignInError.MISSING_CREDENTIALS, repository.signIn.value.error)
        assertFalse(store.hasCredentials())
        assertNull(engine.startedConfig)
    }

    @Test
    fun `SIP registration failure is distinct from API sign in and can recover`() {
        val vm = viewModel { 200 to """{"username":"alice","displayName":"Alice"}""" }
        vm.signIn()
        await { vm.screen.value == AppScreen.Contacts }
        runBlocking { engine.events.emit(CallEvent.RegistrationFailed) }
        await { vm.callRegistrationFailed }
        assertTrue(repository.signIn.value.success)
        runBlocking { engine.events.emit(CallEvent.Registered) }
        await { !vm.callRegistrationFailed }
    }

    @Test
    fun `temporarily offline restored session keeps credentials for retry`() {
        store.save("https://phone.example.com", "alice", "saved-api-token", "saved-sip-password")
        val vm = viewModel(expectedToken = "saved-api-token") { throw IOException("offline") }
        await { vm.screen.value == AppScreen.Onboarding && !repository.signIn.value.inProgress }
        assertEquals(SignInError.NETWORK, repository.signIn.value.error)
        assertTrue(store.hasCredentials())
        assertNull(engine.startedConfig)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "Sign-in state did not settle within five seconds")
    }
}
