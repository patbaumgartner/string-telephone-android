package com.patbaumgartner.android.stringtelephone.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatCacheTest {

    private fun message(clientKey: String, content: String, at: Long, state: SendState = SendState.PENDING) =
        ChatMessage(serverId = null, clientKey = clientKey, fromMe = true, sender = "", content = content, sentAtEpochMs = at, state = state)

    @Test
    fun `append adds and sorts by timestamp`() {
        val cache = ChatCache()
        cache.append("bob", message("k2", "second", 200))
        cache.append("bob", ChatMessage("s1", "k1", false, "", "first", 100, SendState.SENT))
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
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        assertTrue(cache.applyReadReceipt(setOf("m1"), 500))
        val stored = cache.history("bob").single()
        assertEquals(SendState.READ, stored.state)
        assertEquals(500L, stored.readAtEpochMs)
    }

    @Test
    fun `a read receipt only touches the listed server ids`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "one", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "two", 2, SendState.SENT))
        assertTrue(cache.applyReadReceipt(setOf("m2"), 500))
        val states = cache.history("bob").associate { it.serverId to it.state }
        assertEquals(SendState.SENT, states["m1"])
        assertEquals(SendState.READ, states["m2"])
    }

    @Test
    fun `applyReaction adds one emoji per author and replaces on react again`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        assertTrue(cache.applyReaction("m1", "alice", "👍", added = true).let { it != null })

        // Reacting again replaces the emoji (one row per author).
        cache.applyReaction("m1", "alice", "😂", added = true)
        assertEquals(mapOf("alice" to "😂"), cache.history("bob").single().reactions)
    }

    @Test
    fun `applyReaction removes only the named author's reaction`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        cache.applyReaction("m1", "alice", "👍", added = true)
        cache.applyReaction("m1", "bob", "❤️", added = true)

        cache.applyReaction("m1", "alice", "👍", added = false)
        assertEquals(mapOf("bob" to "❤️"), cache.history("bob").single().reactions)

        // Removing again is a no-op.
        cache.applyReaction("m1", "alice", "👍", added = false)
        assertEquals(mapOf("bob" to "❤️"), cache.history("bob").single().reactions)
    }

    @Test
    fun `applyEdited replaces content stamps the hint and keeps receipts`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "helo", 1, SendState.READ, readAtEpochMs = 9L))
        cache.append("bob", ChatMessage("m2", "k2", false, "", "from peer", 2, SendState.SENT))

        assertEquals("bob", cache.applyEdited("m1", "hello", 55L))
        val edited = cache.history("bob").single { it.serverId == "m1" }
        assertEquals("hello", edited.content)
        assertEquals(55L, edited.editedAtEpochMs)
        assertEquals(9L, edited.readAtEpochMs)
        assertEquals(SendState.READ, edited.state)

        // Idempotence: applying the same edit again changes nothing further.
        assertEquals("bob", cache.applyEdited("m1", "hello", 55L))
        assertEquals(1, cache.history("bob").count { it.editedAtEpochMs == 55L })

        // Unknown ids report null; tombstones are left untouched (deletes take
        // precedence) — the tombstone's peer is returned so callers can
        // re-emit, exactly like applyDeleted's early return.
        assertEquals(null, cache.applyEdited("missing", "x", 1L))
        cache.applyDeleted("m1", 77L, "bob")
        cache.applyEdited("m1", "revive", 2L)
        val tombstone = cache.history("bob").single { it.serverId == "m1" }
        assertTrue(tombstone.deleted)
        assertEquals("hello", tombstone.content, "the tombstone keeps the last live content")
        assertEquals(null, tombstone.editedAtEpochMs.takeIf { tombstone.deletedAtEpochMs == 77L && it == 2L })
    }

    @Test
    fun `applyReaction touches only the named server id and reports unknown ids`() {        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "one", 1, SendState.SENT))
        cache.append("carol", ChatMessage("m2", "k2", false, "", "two", 2, SendState.SENT))

        assertEquals("carol", cache.applyReaction("m2", "bob", "🔥", added = true))
        assertEquals(emptyMap(), cache.history("bob").single().reactions)
        assertEquals(mapOf("bob" to "🔥"), cache.history("carol").single().reactions)
        assertEquals(null, cache.applyReaction("missing", "alice", "👍", added = true))
    }

    @Test
    fun `a receipt for an unknown id changes nothing`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        assertTrue(!cache.applyReadReceipt(setOf("other"), 500))
        assertTrue(!cache.applyReadReceipt(emptySet(), 500))
        assertEquals(SendState.SENT, cache.history("bob").single().state)
    }

    @Test
    fun `a read state is never downgraded by a later history page`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        cache.applyReadReceipt(setOf("m1"), 500)
        // A page fetched before the receipt still says unread.
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hey", 1, SendState.SENT))
        val stored = cache.history("bob").single()
        assertEquals(SendState.READ, stored.state)
        assertEquals(500L, stored.readAtEpochMs)
    }

    @Test
    fun `reply carries its target id through the cache`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", false, "", "can you make saturday", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "yes 2pm works", 2, SendState.SENT, replyToId = "m1"))
        val reply = cache.history("bob").single { it.serverId == "m2" }
        assertEquals("m1", reply.replyToId)
    }

    @Test
    fun `findQuotedMessage resolves the referenced message in the same conversation`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", false, "", "can you make saturday", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "yes 2pm works", 2, SendState.SENT, replyToId = "m1"))

        val quoted = cache.findQuotedMessage("bob", "m1")
        assertEquals("can you make saturday", quoted?.content)
        // Unknown ids and other conversations resolve to nothing.
        assertEquals(null, cache.findQuotedMessage("bob", "missing"))
        assertEquals(null, cache.findQuotedMessage("carol", "m1"))
    }

    @Test
    fun `applyDeleted tombstones exactly the named message`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", false, "", "secret", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "other", 2, SendState.SENT))

        val peer = cache.applyDeleted("m1", 500, "bob")
        assertEquals("bob", peer)
        val deleted = cache.history("bob").single { it.serverId == "m1" }
        assertTrue(deleted.deleted)
        assertEquals(500L, deleted.deletedAtEpochMs)
        assertEquals("bob", deleted.deletedBy)
        // Content stays in the local record so a reply quote can still render it.
        assertEquals("secret", deleted.content)
        // The other message is untouched.
        assertTrue(cache.history("bob").single { it.serverId == "m2" }.content.isNotEmpty())
    }

    @Test
    fun `applyDeleted is idempotent and keeps the first tombstone`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", true, "", "hello", 1, SendState.SENT))
        cache.applyDeleted("m1", 500, "alice")
        cache.applyDeleted("m1", 999, "bob")

        val stored = cache.history("bob").single()
        assertEquals(500L, stored.deletedAtEpochMs)
        assertEquals("alice", stored.deletedBy)
    }

    @Test
    fun `hideLocally removes only the named message and does not touch the server record`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage("m1", "k1", false, "", "keep", 1, SendState.SENT))
        cache.append("bob", ChatMessage("m2", "k2", true, "", "hide me", 2, SendState.SENT))

        assertTrue(cache.hideLocally("bob", "k2"))
        assertEquals(listOf("keep"), cache.history("bob").map { it.content })
        // Hiding an unknown key is a no-op.
        assertTrue(!cache.hideLocally("bob", "missing"))
    }

    @Test
    fun `voice fields round-trip through fromServer and the cache`() {
        val message = com.patbaumgartner.android.stringtelephone.network.MessageResult(
            "m1", "bob", "alice", "", 7, "kv", type = "VOICE", mediaId = "media1",
            durationMs = 12_345, waveform = "10,50,90,30",
        )
        val cache = ChatCache()
        cache.append("bob", ChatMessage.fromServer(message, myUsername = "alice"))

        val stored = cache.history("bob").single()
        assertEquals("VOICE", stored.type)
        assertEquals("media1", stored.mediaId)
        assertEquals(12_345, stored.durationMs)
        assertEquals("10,50,90,30", stored.waveform)
    }
}

class ChatCacheReadElsewhereTest {
    private fun message(serverId: String, clientKey: String, fromMe: Boolean, state: SendState) =
        ChatMessage(serverId = serverId, clientKey = clientKey, fromMe = fromMe, sender = "", content = "x", sentAtEpochMs = 1, state = state)

    @Test
    fun `applyReadReceiptForPeers returns the peers whose messages actually changed`() {
        val cache = ChatCache()
        cache.append("bob", message("m1", "k1", fromMe = true, state = SendState.SENT))
        cache.append("carol", message("m2", "k2", fromMe = true, state = SendState.SENT))

        assertEquals(setOf("bob", "carol"), cache.applyReadReceiptForPeers(setOf("m1", "m2"), 500))

        // A repeated receipt is idempotent: no peer reported as changed.
        assertEquals(emptySet(), cache.applyReadReceiptForPeers(setOf("m1", "m2"), 500))
    }

    @Test
    fun `applyReadReceiptForPeers ignores unknown ids and empty sets`() {
        val cache = ChatCache()
        cache.append("bob", message("m1", "k1", fromMe = true, state = SendState.SENT))
        assertEquals(emptySet(), cache.applyReadReceiptForPeers(setOf("other"), 500))
        assertEquals(emptySet(), cache.applyReadReceiptForPeers(emptySet(), 500))
        assertEquals(SendState.SENT, cache.history("bob").single().state)
    }
}
