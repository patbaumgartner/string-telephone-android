package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.network.MeResult
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Read receipts (ADR-0010): a message counts as read only while the
 * conversation is open on the phone. The tests drive the real API client
 * against a recording HTTP layer, so the wire format is covered too.
 */
class StringTelephoneRepositoryReadReceiptTest {

    /** A recorded request: method, path and body. */
    private data class Call(val method: String, val path: String, val body: String)

    /** Answers every request from [handler] and remembers what was asked. */
    private class FakeHttp(private val handler: (Call) -> Pair<Int, String>) {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Call>())

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val call = Call(request.method, request.url.encodedPath, buffer.readUtf8())
                calls += call
                val (code, payload) = handler(call)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(payload.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private class FakeRealtimeStream : RealtimeStream {
        private val flow = MutableSharedFlow<RealtimeEvent>(replay = 16)
        override val events: SharedFlow<RealtimeEvent> = flow
        override fun connect() = Unit
        override fun disconnect() = Unit
        suspend fun emit(event: RealtimeEvent) = flow.emit(event)
    }

    private fun repository(cache: ChatCache, http: FakeHttp, stream: RealtimeStream? = null) =
        StringTelephoneRepository(
            apiProvider = { CompanionApiClient("https://phone.example.com", { "token" }, http.client()) },
            chatCache = cache,
            realtimeFactory = { stream },
        )

    @Test
    fun `a cursor catch-up never marks the conversation read`() = runBlocking {
        val http = FakeHttp { call ->
            if (call.path.endsWith("/messages")) {
                200 to """{"messages":[{"id":"m1","sender":"bob","recipient":"alice","content":"hi","createdAt":5,"clientKey":"k1"}],"nextCursor":null}"""
            } else {
                200 to "{}"
            }
        }
        val cache = ChatCache()
        val repo = repository(cache, http)

        repo.loadHistory("bob", "alice")
        awaitUntil { cache.history("bob").isNotEmpty() }
        delay(200)

        assertEquals(1, cache.history("bob").size)
        assertTrue(
            http.calls.none { it.path.endsWith("/read") },
            "a background history load must never mark the conversation read",
        )
    }

    @Test
    fun `the foreground chat screen marks the rendered messages read`() = runBlocking {
        val http = FakeHttp { call ->
            if (call.path.endsWith("/read")) 200 to """{"messageIds":["m1"],"readAt":77}""" else 200 to "{}"
        }
        val cache = ChatCache()
        val repo = repository(cache, http)
        cache.append("bob", ChatMessage("m1", "k1", false, "hi", 5, SendState.SENT))

        repo.markConversationRead("bob", "m1")
        awaitUntil { http.calls.any { it.path.endsWith("/read") } }

        val read = http.calls.single { it.path.endsWith("/read") }
        assertEquals("POST", read.method)
        assertEquals("/v1/conversations/bob/read", read.path)
        assertEquals("""{"upToMessageId":"m1"}""", read.body)
        awaitUntil { cache.history("bob").single().state == SendState.READ }
        assertEquals(77L, cache.history("bob").single().readAtEpochMs)
    }

    @Test
    fun `a read receipt frame turns the sent copy into a double tick`() = runBlocking {
        val http = FakeHttp { 200 to "{}" }
        val stream = FakeRealtimeStream()
        val cache = ChatCache()
        val repo = repository(cache, http, stream)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        cache.append("bob", ChatMessage("m1", "k1", true, "hi", 5, SendState.SENT))

        repo.startRealtime()
        stream.emit(RealtimeEvent.MessageRead(listOf("m1"), 42))

        awaitUntil { cache.history("bob").single().state == SendState.READ }
        assertEquals(42L, cache.history("bob").single().readAtEpochMs)
        repo.stopRealtime()
    }

    @Test
    fun `a failed read mark is not worth surfacing`() = runBlocking {
        val http = FakeHttp { 500 to """{"error":"boom"}""" }
        val cache = ChatCache()
        val repo = repository(cache, http)
        cache.append("bob", ChatMessage("m1", "k1", false, "hi", 5, SendState.SENT))

        repo.markConversationRead("bob", "m1")
        awaitUntil { http.calls.isNotEmpty() }
        delay(200)

        assertEquals(SendState.SENT, cache.history("bob").single().state)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }
}
