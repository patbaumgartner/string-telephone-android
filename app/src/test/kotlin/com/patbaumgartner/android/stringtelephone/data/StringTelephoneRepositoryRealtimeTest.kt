package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.RealtimeEvent
import com.patbaumgartner.android.stringtelephone.network.MeResult
import com.patbaumgartner.android.stringtelephone.network.RealtimeStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
                state = SendState.QUEUED,
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

    private suspend fun awaitCount(
        cache: ChatCache,
        peer: String,
        count: Int,
    ): List<ChatMessage> = withTimeout(5_000) {
        while (cache.history(peer).size < count) delay(10)
        cache.history(peer)
    }
}
