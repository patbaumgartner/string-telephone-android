package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.network.MeResult
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringTelephoneRepositoryRealtimeTest {

    /** In-memory stand-in for the websocket; [replay] avoids subscribe races. */
    private class FakeRealtimeStream : RealtimeStream {
        private val flow = MutableSharedFlow<RealtimeEvent>(replay = 16)
        override val events: SharedFlow<RealtimeEvent> = flow
        var connected = false
        var disconnected = false

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
        }

        suspend fun emit(event: RealtimeEvent) = flow.emit(event)
    }

    private fun repository(cache: ChatCache, stream: RealtimeStream): StringTelephoneRepository =
        StringTelephoneRepository(
            apiProvider = { null },
            chatCache = cache,
            realtimeFactory = { stream },
        )

    @Test
    fun `an incoming frame lands in the peer chat cache`() = runBlocking {
        val cache = ChatCache()
        val fake = FakeRealtimeStream()
        val repo = repository(cache, fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())

        repo.startRealtime()
        assertTrue(fake.connected)
        fake.emit(RealtimeEvent.NewMessage("m1", "bob", "alice", "hi there", 1_000, "k1"))

        val history = awaitCount(cache, "bob", 1)
        assertEquals("hi there", history.single().content)
        assertFalse(history.single().fromMe)

        repo.stopRealtime()
        assertTrue(fake.disconnected)
    }

    @Test
    fun `my own echoed frame does not duplicate the optimistic message`() = runBlocking {
        val cache = ChatCache()
        val fake = FakeRealtimeStream()
        val repo = repository(cache, fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        cache.append(
            "bob",
            ChatMessage(
                serverId = null,
                clientKey = "k1",
                fromMe = true,
                content = "hi",
                sentAtEpochMs = 1_000,
                state = SendState.PENDING,
            ),
        )

        repo.startRealtime()
        // The server echoes my own message with the same client key.
        fake.emit(RealtimeEvent.NewMessage("m1", "alice", "bob", "hi", 1_000, "k1"))
        delay(300)
        assertEquals(1, cache.history("bob").size, "own echo must not duplicate")

        // A later peer frame still arrives, proving the stream stayed live.
        fake.emit(RealtimeEvent.NewMessage("m2", "bob", "alice", "reply", 2_000, "k2"))
        val history = awaitCount(cache, "bob", 2)
        assertEquals("reply", history.last().content)
        repo.stopRealtime()
    }

    @Test
    fun `incoming messages count as unread unless their chat is open`() = runBlocking {
        val cache = ChatCache()
        val fake = FakeRealtimeStream()
        val repo = repository(cache, fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        val incoming = mutableListOf<IncomingMessage>()
        val collector = launch(Dispatchers.Unconfined) { repo.incomingMessages.collect { incoming += it } }

        repo.startRealtime()
        fake.emit(RealtimeEvent.NewMessage("m1", "bob", "alice", "one", 1_000, "k1"))
        fake.emit(RealtimeEvent.NewMessage("m2", "bob", "alice", "two", 2_000, "k2"))
        // A repeated frame (e.g. after a reconnect) is neither counted nor re-notified.
        fake.emit(RealtimeEvent.NewMessage("m2", "bob", "alice", "two", 2_000, "k2"))
        fake.emit(RealtimeEvent.NewMessage("m3", "alice", "carol", "mine", 3_000, "k3"))
        awaitUntil { repo.unread.value["bob"] == 2 && incoming.size == 2 }
        delay(100)
        assertEquals(mapOf("bob" to 2), repo.unread.value)
        assertEquals(listOf("one", "two"), incoming.map { it.content })

        repo.setActiveConversation("bob")
        assertEquals(emptyMap(), repo.unread.value)
        fake.emit(RealtimeEvent.NewMessage("m4", "bob", "alice", "seen", 4_000, "k4"))
        awaitCount(cache, "bob", 3)
        assertEquals(emptyMap(), repo.unread.value)
        assertTrue(repo.isActiveConversation("bob"))

        repo.setActiveConversation(null)
        fake.emit(RealtimeEvent.NewMessage("m5", "bob", "alice", "later", 5_000, "k5"))
        awaitUntil { repo.unread.value["bob"] == 1 }

        repo.clearSession()
        assertEquals(emptyMap(), repo.unread.value)
        collector.cancel()
    }

    @Test
    fun `presence frames update the matching contact`() = runBlocking {
        val fake = FakeRealtimeStream()
        val repo = repository(ChatCache(), fake)
        repo.seedForPreviews(
            MeResult("alice", "Alice"),
            listOf(ContactDto("bob", "Bob", "offline"), ContactDto("carol", "Carol", "offline")),
        )

        repo.startRealtime()
        fake.emit(RealtimeEvent.Presence("bob", "online", 7_000))
        awaitUntil { repo.contacts.value.contacts.first { it.username == "bob" }.presence == "online" }
        val bob = repo.contacts.value.contacts.first { it.username == "bob" }
        assertEquals(7_000L, bob.presenceUpdatedAtEpochMs)
        assertEquals("offline", repo.contacts.value.contacts.first { it.username == "carol" }.presence)
        repo.stopRealtime()
    }

    @Test
    fun `typing indicator shows peer, expires after 5 seconds`() = runBlocking {
        val fake = FakeRealtimeStream()
        val repo = repository(ChatCache(), fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        repo.startRealtime()

        assertEquals(null, repo.typingPeer.value)
        fake.emit(RealtimeEvent.Typing("bob", true))
        awaitUntil { repo.typingPeer.value == "bob" }

        // A refresh resets the expiry clock.
        fake.emit(RealtimeEvent.Typing("bob", true))
        assertEquals("bob", repo.typingPeer.value)

        // After the expiry window, the indicator clears.
        delay(StringTelephoneRepository.TYPING_EXPIRY_MS + 100)
        assertEquals(null, repo.typingPeer.value)
        repo.stopRealtime()
    }

    @Test
    fun `stop-typing signal clears indicator immediately`() = runBlocking {
        val fake = FakeRealtimeStream()
        val repo = repository(ChatCache(), fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        repo.startRealtime()

        fake.emit(RealtimeEvent.Typing("bob", true))
        awaitUntil { repo.typingPeer.value == "bob" }
        fake.emit(RealtimeEvent.Typing("bob", false))
        awaitUntil { repo.typingPeer.value == null }
        repo.stopRealtime()
    }

    @Test
    fun `sendTyping(false) clears the indicator and cancels the expiry`() = runBlocking {
        val fake = FakeRealtimeStream()
        val repo = repository(ChatCache(), fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        repo.startRealtime()

        fake.emit(RealtimeEvent.Typing("bob", true))
        awaitUntil { repo.typingPeer.value == "bob" }
        repo.sendTyping(false)
        assertEquals(null, repo.typingPeer.value)
        // Even after the expiry window nothing reappears.
        delay(StringTelephoneRepository.TYPING_EXPIRY_MS + 100)
        assertEquals(null, repo.typingPeer.value)
        repo.stopRealtime()
    }

    @Test
    fun `a reaction frame updates the cached message`() = runBlocking {
        val cache = ChatCache()
        val fake = FakeRealtimeStream()
        val repo = repository(cache, fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        cache.append("bob", ChatMessage("m1", "k1", true, "hey", 1, SendState.SENT))

        repo.startRealtime()
        fake.emit(RealtimeEvent.MessageReaction("m1", "bob", "bob", "👍", added = true))
        awaitUntil { cache.history("bob").single().reactions.containsKey("bob") }
        assertEquals(mapOf("bob" to "👍"), cache.history("bob").single().reactions)

        fake.emit(RealtimeEvent.MessageReaction("m1", "bob", "bob", "👍", added = false))
        awaitUntil { cache.history("bob").single().reactions.isEmpty() }
        repo.stopRealtime()
    }

    @Test
    fun `a deletion frame tombstones the cached message`() = runBlocking {
        val cache = ChatCache()
        val fake = FakeRealtimeStream()
        val repo = repository(cache, fake)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        cache.append("bob", ChatMessage("m1", "k1", false, "secret", 1, SendState.SENT))

        repo.startRealtime()
        fake.emit(RealtimeEvent.MessageDeleted("m1", "alice", "bob", 77))
        awaitUntil { cache.history("bob").single().deleted }
        val deleted = cache.history("bob").single()
        assertTrue(deleted.deleted)
        assertEquals(77L, deleted.deletedAtEpochMs)
        assertEquals("bob", deleted.deletedBy)
        repo.stopRealtime()
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private suspend fun awaitCount(
        cache: ChatCache,
        peer: String,
        count: Int,
    ): List<ChatMessage> = withTimeout(5_000) {
        while (cache.history(peer).size < count) delay(10)
        cache.history(peer)
    }
}
