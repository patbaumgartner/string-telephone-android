package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals

class StringTelephoneRepositorySendTest {

    private val keyPattern = Regex("\"clientKey\":\"([^\"]+)\"")

    @Test
    fun `a failed message is resent with its original client key`() = runBlocking {
        val sentKeys = Collections.synchronizedList(mutableListOf<String>())
        val online = AtomicBoolean(false)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val key = keyPattern.find(body)!!.groupValues[1]
            sentKeys += key
            if (!online.get()) throw IOException("offline")
            val reply = """{"id":"srv-1","sender":"alice","recipient":"bob","content":"hi","createdAt":1,"clientKey":"$key"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(201).message("Created").body(reply.toResponseBody()).build()
        }.build()
        val client = CompanionApiClient("https://phone.example.com", { "test-token" }, http)
        val cache = ChatCache()
        val repo = StringTelephoneRepository(apiProvider = { client }, chatCache = cache)

        repo.send("bob", "hi") {}
        awaitState(cache, SendState.FAILED)

        online.set(true)
        repo.resend("bob", cache.history("bob").single())
        awaitState(cache, SendState.SENT)

        val stored = cache.history("bob").single()
        assertEquals("srv-1", stored.serverId)
        assertEquals(listOf(stored.clientKey, stored.clientKey), sentKeys.toList())
    }

    @Test
    fun `sending without a server keeps the message visible as failed`() = runBlocking {
        val cache = ChatCache()
        val repo = StringTelephoneRepository(apiProvider = { null }, chatCache = cache)

        repo.send("bob", "hi") {}

        awaitState(cache, SendState.FAILED)
        assertEquals("hi", cache.history("bob").single().content)
    }

    private suspend fun awaitState(cache: ChatCache, state: SendState) = withTimeout(5_000) {
        while (cache.history("bob").singleOrNull()?.state != state) delay(10)
    }
}
