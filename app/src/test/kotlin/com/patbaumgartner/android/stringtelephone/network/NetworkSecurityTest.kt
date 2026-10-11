package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.BuildConfig
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class NetworkSecurityTest {
    @Test
    fun `production accepts HTTPS but rejects persisted plaintext and invalid URLs`() {
        requireAllowedServerUrl("https://phone.example.com", development = false)
        for (url in listOf("http://phone.example.com", "ftp://phone.example.com", "https://")) {
            assertFailsWith<IllegalArgumentException> { requireAllowedServerUrl(url, development = false) }
        }
    }

    @Test
    fun `development explicitly permits HTTP and WS`() {
        requireAllowedServerUrl("http://127.0.0.1:8080", development = true)
    }

    @Test
    fun `network boundary uses the actual build type for plaintext policy`() {
        if (BuildConfig.DEBUG) {
            requireAllowedServerUrl("http://127.0.0.1:8080")
        } else {
            assertFailsWith<IllegalArgumentException> { requireAllowedServerUrl("http://127.0.0.1:8080") }
        }
    }

    @Test
    fun `HTTPS clients cannot redirect to plaintext and retain platform certificate checks`() {
        val original = OkHttpClient()
        val secured = original.withTransportSecurity()
        assertFalse(secured.followSslRedirects)
        assertSame(original.hostnameVerifier, secured.hostnameVerifier)
        assertSame(original.sslSocketFactory, secured.sslSocketFactory)
    }
}
