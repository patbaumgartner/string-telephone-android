package com.patbaumgartner.android.stringtelephone.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conversation previews (Phase 15): the newest message per peer is exposed
 * for the list row, following ADR-0011's preview rules — a tombstoned
 * message previews as deleted, reactions never change the preview.
 */
class ChatCachePreviewTest {

    @Test
    fun `preview returns the newest message per peer`() {
        val cache = ChatCache()
        assertNull(cache.preview("bob"))

        cache.append("bob", ChatMessage(null, "k1", fromMe = false, "hello", 1_000, SendState.SENT))
        cache.append("bob", ChatMessage(null, "k2", fromMe = true, "hi back", 2_000, SendState.PENDING))

        assertEquals("hi back", cache.preview("bob")?.content)
    }

    @Test
    fun `a tombstoned message still previews as itself and the UI maps it to deleted`() {
        val cache = ChatCache()
        val tombstone = ChatMessage("srv-1", "k1", fromMe = false, "", 1_000, SendState.SENT, deletedAtEpochMs = 5_000, deletedBy = null)
        cache.append("bob", tombstone)

        // The preview is the message; the localised "deleted" wording is the UI's job.
        assertEquals(true, cache.preview("bob")?.deleted)
    }

    @Test
    fun `preview updates when the state of the newest message changes`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage(null, "k1", fromMe = true, "hello", 1_000, SendState.PENDING))

        cache.markSent("k1", "srv-1")
        assertEquals(SendState.SENT, cache.preview("bob")?.state)

        cache.markFailed("k1")
        assertEquals(SendState.FAILED, cache.preview("bob")?.state)
    }

    @Test
    fun `peers lists every conversation that has messages`() {
        val cache = ChatCache()
        assertEquals(emptyList(), cache.peers())

        cache.append("bob", ChatMessage(null, "k1", fromMe = false, "hi", 1_000, SendState.SENT))
        cache.append("carol", ChatMessage(null, "k2", fromMe = false, "ho", 2_000, SendState.SENT))

        assertEquals(setOf("bob", "carol"), cache.peers().toSet())
    }

    @Test
    fun `a reaction alone does not change the preview text`() {
        val cache = ChatCache()
        cache.append("bob", ChatMessage(null, "k1", fromMe = false, "older", 500, SendState.SENT))
        cache.append("bob", ChatMessage("srv-1", "k2", fromMe = false, "nice", 1_000, SendState.SENT))

        cache.applyReaction("srv-1", "alice", "👍", added = true)

        // The reaction is applied but the preview text is untouched (ADR-0011).
        assertEquals("nice", cache.preview("bob")?.content)
        assertEquals(mapOf("alice" to "👍"), cache.preview("bob")?.reactions)
    }
}
