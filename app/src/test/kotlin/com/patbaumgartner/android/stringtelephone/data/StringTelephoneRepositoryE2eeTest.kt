package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.KyberPreKeyDto
import com.patbaumgartner.android.stringtelephone.core.KeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.OneTimePreKeyDto
import com.patbaumgartner.android.stringtelephone.core.PeerKeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.core.SignedPreKeyDto
import com.patbaumgartner.android.stringtelephone.core.e2ee.E2eeEngine
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private val dummySigned = SignedPreKeyDto(1, "signed-pub", "signed-sig")
private val dummyKyber = KyberPreKeyDto(2, "kyber-pub", "kyber-sig")

/** End-to-end encryption seam tests (ADR-0015, Phase 22): the repository encrypts outgoing TEXT, decrypts incoming TEXT, fails honestly when a peer has no keys, and searches client-side over decrypted cache. */
class StringTelephoneRepositoryE2eeTest {

    /** Stand-in engine: encrypt returns a reversible marker envelope; `setChanged` flips identity-change warnings. */
    private class FakeE2eeEngine : E2eeEngine {
        override val identityKeyPublicBase64 = "fake-identity-base64"
        override val registrationId = 5
        private val sessions = mutableSetOf<String>()
        private val changed = mutableSetOf<String>()

        fun setChanged(peer: String, changed: Boolean) {
            if (changed) this.changed += peer else this.changed.remove(peer)
        }

        override fun hasSession(peer: String) = sessions.contains(peer)
        override fun buildKeyBundle(oneTimePreKeyCount: Int): KeyBundleDto =
            KeyBundleDto(registrationId, identityKeyPublicBase64, dummySigned, dummyKyber, emptyList())
        override fun processPeerBundle(peer: String, bundle: PeerKeyBundleDto, oneTimePreKey: OneTimePreKeyDto?): kotlin.Result<Unit> {
            sessions += peer
            return kotlin.Result.success(Unit)
        }
        override fun encrypt(peer: String, plaintext: String): kotlin.Result<String> =
            kotlin.Result.success("2:ok:$plaintext")
        override fun decrypt(peer: String, envelope: String): String? =
            if (envelope.startsWith("2:ok:")) envelope.substringAfter("2:ok:") else null
        override fun isIdentityTrusted(peer: String, identityKeyBase64: String) = !changed.contains(peer)
        override fun trustIdentity(peer: String, identityKeyBase64: String) {}
        override fun safetyNumber(peer: String): String? =
            "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890"
        override fun identityChanged(peer: String): Boolean = changed.contains(peer)
        override fun clearIdentityChange(peer: String) { changed.remove(peer) }
    }

    /** In-memory stand-in for the websocket; [replay] avoids subscribe races. */
    private class FakeRealtimeStream : RealtimeStream {
        private val flow = kotlinx.coroutines.flow.MutableSharedFlow<RealtimeEvent>(replay = 16)
        override val events: kotlinx.coroutines.flow.SharedFlow<RealtimeEvent> = flow
        override fun connect() {}
        override fun disconnect() {}
        suspend fun emit(event: RealtimeEvent) = flow.emit(event)
    }

    private val me = com.patbaumgartner.android.stringtelephone.network.MeResult("alice", "Alice")

    private fun repository(
        cache: ChatCache,
        engine: E2eeEngine,
        stream: RealtimeStream,
        routes: (request: okhttp3.Request) -> okhttp3.Response?,
    ): StringTelephoneRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            routes(chain.request()) ?: fail("unrouted request: ${chain.request().method} ${chain.request().url}")
        }.build()
        val client = CompanionApiClient("https://phone.example.com", { "test-token" }, http)
        return StringTelephoneRepository(
            apiProvider = { client },
            chatCache = cache,
            realtimeFactory = { stream },
            e2ee = engine,
        )
    }

    private fun ok(body: String, code: Int = 200, message: String = "OK"): okhttp3.Response =
        okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://phone.example.com/x").get().build())
            .code(code)
            .message(message)
            .protocol(Protocol.HTTP_1_1)
            .body(body.toResponseBody())
            .build()

    private suspend fun awaitState(cache: ChatCache, peer: String, state: SendState) = withTimeout(5_000) {
        while (cache.history(peer).singleOrNull()?.state != state) delay(10)
        cache.history(peer)
    }

    private fun encryptedNewMessage(id: String, content: String, envelope: String?): RealtimeEvent.NewMessage =
        RealtimeEvent.NewMessage(id, "bob", "alice", content, 1_000, "$id-key", encrypted = true, envelope = envelope)

    @Test
    fun `sending encrypts for peer plus self, blanks content, and marks sent`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        val bodies = mutableListOf<String>()
        val repo = repository(cache, engine, stream) { request ->
            when {
                request.url.toString() == "https://phone.example.com/v1/keys/bob" -> ok(
                    """{"username":"bob","registrationId":7,"identityKey":"identity-bob","signedPreKey":{"id":1,"publicKey":"signed-pub","signature":"signed-sig"},"kyberPreKey":{"id":2,"publicKey":"kyber-pub","signature":"kyber-sig"},"oneTimePrekeyCount":3}""")
                request.url.toString() == "https://phone.example.com/v1/keys/bob/one-time" -> ok(
                    """{"id":4,"publicKey":"otk-4"}""")
                request.url.toString() == "https://phone.example.com/v1/keys/me" -> ok(
                    """{"registrationId":7,"identityKey":"identity-alice","signedPreKey":{"id":1,"publicKey":"signed-pub","signature":"signed-sig"},"kyberPreKey":{"id":2,"publicKey":"kyber-pub","signature":"kyber-sig"},"oneTimePrekeyCount":3}""")
                request.url.toString().contains("/v1/conversations/bob/messages") && request.method == "POST" -> {
                    bodies.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    ok(
                        """{"id":"srv-1","sender":"alice","recipient":"bob","content":"","createdAt":1,"clientKey":"k1"}""",
                        code = 201,
                        message = "Created",
                    )
                }
                else -> fail("unrouted: ${request.method} ${request.url}")
            }
        }
        repo.seedForPreviews(me, emptyList())
        repo.startRealtime()

        repo.send("bob", "hello") {}
        awaitState(cache, "bob", SendState.SENT)

        val stored = cache.history("bob").single()
        assertEquals("srv-1", stored.serverId)
        assertEquals(SendState.SENT, stored.state)
        // Encrypt happened for both participants; envelopes present for both.
        assertTrue(engine.hasSession("bob") && engine.hasSession("alice"))
        assertEquals(1, bodies.size)
        val body = bodies.single()
        // `content` defaults to "" so the serializer omits it entirely when
        // blanked — its absence proves plaintext never left the device.
        assertFalse(body.contains("\"content\""), "content field must be omitted when E2EE envelopes are present: $body")
        assertTrue(body.contains("\"bob\":\"2:ok:hello\"") && body.contains("\"alice\":\"2:ok:hello\""), "envelopes for both participants: $body")
        assertTrue(engine.decrypt("bob", "2:ok:hello") == "hello")
        repo.stopRealtime()
    }

    @Test
    fun `send fails honestly when the peer has no keys and nothing is POSTed`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        var posted = false
        val repo = repository(cache, engine, stream) { request ->
            if (request.url.toString().startsWith("https://phone.example.com/v1/keys/")) {
                ok("", code = 404, message = "Not Found")
            } else {
                posted = true
                fail("must not POST when peer keys are missing: ${request.method} ${request.url}")
            }
        }
        repo.seedForPreviews(me, emptyList())

        repo.send("bob", "hello") {}
        awaitState(cache, "bob", SendState.FAILED)
        assertFalse(posted)
        assertEquals(SendState.FAILED, cache.history("bob").single().state)
        repo.stopRealtime()
    }

    @Test
    fun `encrypted incoming frame decrypts into cache and the incoming message`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        val repo = repository(cache, engine, stream) { _ -> null }
        repo.seedForPreviews(me, emptyList())
        val incoming = mutableListOf<IncomingMessage>()
        val collector = launch(Dispatchers.Unconfined) { repo.incomingMessages.collect { incoming += it } }

        repo.startRealtime()
        stream.emit(encryptedNewMessage("m1", "", "2:ok:secret from bob"))

        val history = withTimeout(5_000) {
            while (cache.history("bob").isEmpty()) delay(10)
            cache.history("bob")
        }
        assertEquals(1, history.size)
        assertEquals("secret from bob", history.single().content)
        assertEquals("secret from bob", incoming.single().content)
        collector.cancel()
        repo.stopRealtime()
    }

    @Test
    fun `undecryptable envelope shows the placeholder, never garbage`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        val repo = repository(cache, engine, stream) { _ -> null }
        repo.seedForPreviews(me, emptyList())
        repo.startRealtime()

        stream.emit(encryptedNewMessage("m2", "", "not-an-envelope"))

        val history = withTimeout(5_000) {
            while (cache.history("bob").isEmpty()) delay(10)
            cache.history("bob")
        }
        assertEquals(StringTelephoneRepository.UNDECRYPTABLE_CONTENT, history.single().content)
        repo.stopRealtime()
    }

    @Test
    fun `identity change surfaces a warning and acknowledge clears it`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        val repo = repository(cache, engine, stream) { _ -> null }
        repo.seedForPreviews(me, emptyList())
        repo.startRealtime()

        engine.setChanged("bob", true)
        stream.emit(encryptedNewMessage("m3", "", "2:ok:changed-key text"))

        withTimeout(5_000) { while (repo.identityWarnings.value.isEmpty()) delay(10) }
        assertTrue(repo.identityWarnings.value.contains("bob"))

        repo.acknowledgeIdentityChange("bob")
        assertTrue(repo.identityWarnings.value.isEmpty())
        repo.stopRealtime()
    }

    @Test
    fun `search runs client-side over decrypted cache`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        val repo = repository(cache, engine, stream) { _ -> null }
        repo.seedForPreviews(me, emptyList())

        cache.append("bob", ChatMessage("m1", "k1", false, "", "hello world", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "goodbye world", 2, SendState.SENT))
        cache.append("carol", ChatMessage("m3", "k3", false, "", "hello carol", 3, SendState.SENT))

        val results = repo.search("bob", "world")
        assertEquals(2, results.size)
        assertTrue(results.all { it.content.contains("world", ignoreCase = true) })
        assertTrue(results.all { it.serverId in listOf("m1", "m2") })
    }

    @Test
    fun `publishE2eeKeys publishes the bundle when one-time prekeys are below the floor`() = runBlocking {
        val cache = ChatCache()
        val engine = FakeE2eeEngine()
        val stream = FakeRealtimeStream()
        var published = false
        val repo = repository(cache, engine, stream) { request ->
            when {
                request.url.toString() == "https://phone.example.com/v1/keys/me" -> ok(
                    """{"registrationId":7,"identityKey":"identity-alice","signedPreKey":{"id":1,"publicKey":"signed-pub","signature":"signed-sig"},"kyberPreKey":{"id":2,"publicKey":"kyber-pub","signature":"kyber-sig"},"oneTimePrekeyCount":3}""")
                request.url.toString() == "https://phone.example.com/v1/keys" && request.method == "PUT" -> {
                    published = true
                    ok("", code = 204, message = "No Content")
                }
                else -> fail("unrouted: ${request.method} ${request.url}")
            }
        }
        repo.seedForPreviews(me, emptyList())
        repo.publishE2eeKeys()
        withTimeout(5_000) { while (!published) delay(10) }
        assertTrue(published)
        repo.stopRealtime()
    }
}
