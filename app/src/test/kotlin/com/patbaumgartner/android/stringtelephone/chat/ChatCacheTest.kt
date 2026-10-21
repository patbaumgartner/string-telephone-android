package com.patbaumgartner.android.stringtelephone.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatCacheTest {

    private fun message(clientKey: String, content: String, at: Long, state: SendState = SendState.PENDING) =
        ChatMessage(serverId = null, clientKey = clientKey, fromMe = true, content = content, sentAtEpochMs = at, state = state)

    @Test
    fun `append adds and sorts by timestamp`() {
        val cache = ChatCache()
        cache.append("bob", message("k2", "second", 200))
        cache.append("bob", ChatMessage("s1", "k1", false, "first", 100, SendState.SENT))
        assertEquals(listOf("first", "second"), cache.history("bob").map { it.content })
    }

    @Test
    fun `append is idempotent per client key`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "hello", 1))
        cache.append("bob", message("k1", "hello", 999))
        assertEquals(1, cache.history("bob").size)
    }

    @Test
    fun `markSent stamps server id and sent state`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "hello", 1))
        cache.markSent("k1", "srv-9")
        val stored = cache.history("bob").single()
        assertEquals("srv-9", stored.serverId)
        assertEquals(SendState.SENT, stored.state)
    }

    @Test
    fun `only failed messages can be queued again for a retry`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "hello", 1))
        cache.append("bob", message("k2", "sent", 2, SendState.SENT))
        cache.markFailed("k1")

        assertTrue(cache.markRetrying("k1"))
        assertEquals(SendState.PENDING, cache.history("bob").first().state)
        assertTrue(!cache.markRetrying("k1"))
        assertTrue(!cache.markRetrying("k2"))
        assertTrue(!cache.markRetrying("missing"))
    }

    @Test
    fun `markFailed only affects the matching key`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "a", 1))
        cache.append("bob", message("k2", "second", 2))
        cache.markFailed("k2")
        val states = cache.history("bob").associate { it.clientKey to it.state }
        assertEquals(SendState.PENDING, states["k1"])
        assertEquals(SendState.FAILED, states["k2"])
    }

    @Test
    fun `conversations are independent`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "to bob", 1))
        cache.append("carol", message("k2", "to carol", 2))
        assertEquals(listOf("to bob"), cache.history("bob").map { it.content })
        assertEquals(listOf("to carol"), cache.history("carol").map { it.content })
    }

    @Test
    fun `clear removes every conversation`() {
        val cache = ChatCache()
        cache.append("bob", message("k1", "x", 1))
        cache.append("carol", message("k2", "y", 2))
        cache.clear()
        assertTrue(cache.history("bob").isEmpty())
        assertTrue(cache.history("carol").isEmpty())
    }

    @Test
    fun `fromServer maps sender identity to fromMe`() {
        val fromServer = ChatMessage.fromServer(
            com.patbaumgartner.android.stringtelephone.network.MessageResult("m1", "alice", "bob", "hey", 5, "k9"),
            myUsername = "alice",
        )
        assertTrue(fromServer.fromMe)
        val incoming = ChatMessage.fromServer(
            com.patbaumgartner.android.stringtelephone.network.MessageResult("m2", "bob", "alice", "yo", 6, "k2"),
            myUsername = "alice",
        )
        assertTrue(!incoming.fromMe)
    }

    @Test
    fun `a read receipt turns the sent copy into a double tick`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "hey", 1, SendState.SENT))
        assertTrue(cache.applyReadReceipt(setOf("m1"), 500))
        val stored = cache.history("bob").single()
        assertEquals(SendState.READ, stored.state)
        assertEquals(500L, stored.readAtEpochMs)
    }

    @Test
    fun `a read receipt only touches the listed server ids`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "one", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "two", 2, SendState.SENT))
        assertTrue(cache.applyReadReceipt(setOf("m2"), 500))
        val states = cache.history("bob").associate { it.serverId to it.state }
        assertEquals(SendState.SENT, states["m1"])
        assertEquals(SendState.READ, states["m2"])
    }

    @Test
    fun `a receipt for an unknown id changes nothing`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "hey", 1, SendState.SENT))
        assertTrue(!cache.applyReadReceipt(setOf("other"), 500))
        assertTrue(!cache.applyReadReceipt(emptySet(), 500))
        assertEquals(SendState.SENT, cache.history("bob").single().state)
    }

    @Test
    fun `a read state is never downgraded by a later history page`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "hey", 1, SendState.SENT))
        cache.applyReadReceipt(setOf("m1"), 500)
        // A page fetched before the receipt still says unread.
        cache.append("bob", ChatMessage("m1", "k1", true, "hey", 1, SendState.SENT))
        val stored = cache.history("bob").single()
        assertEquals(SendState.READ, stored.state)
        assertEquals(500L, stored.readAtEpochMs)
    }
}
