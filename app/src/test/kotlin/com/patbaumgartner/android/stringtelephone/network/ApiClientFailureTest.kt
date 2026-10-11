package com.patbaumgartner.android.stringtelephone.network

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

class ApiClientFailureTest {
    private val requests: List<(CompanionApiClient) -> Any> = listOf(
        { it.me() },
        { it.contacts() },
        { it.messages("bob", null) },
        { it.sendMessage("bob", "hello", "test-key") },
    )

    @Test
    fun `all endpoints turn IO failures into visible network errors`() {
        val http = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val client = CompanionApiClient("https://phone.example.com", { "test-token" }, http)
        requests.forEach { request ->
            assertFailsWith<ApiException.Network> { request(client) }
        }
    }

    @Test
    fun `all endpoints turn malformed responses into protocol errors`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("<html>wrong service</html>".toResponseBody()).build()
        }.build()
        val client = CompanionApiClient("https://phone.example.com", { "test-token" }, http)
        requests.forEach { request ->
            assertFailsWith<ApiException.Protocol> { request(client) }
        }
    }
}
