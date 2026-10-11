package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainTest {

    @Test
    fun `conversation id is symmetric in user order`() {
        assertEquals(conversationIdBetween("alice", "bob"), conversationIdBetween("bob", "alice"))
        assertEquals("dm-alice-bob", conversationIdBetween("alice", "bob"))
    }

    @Test
    fun `conversation key is symmetric and colon separated`() {
        assertEquals(conversationKey("bob", "alice"), conversationKey("alice", "bob"))
        assertTrue(conversationKey("bob", "alice").startsWith("dm:"))
    }

    @Test
    fun `presence is online only within the freshness window`() {
        val now = 1_000_000L
        assertEquals(
            PresenceStatus.ONLINE,
            effectivePresence(registered = true, observedAtEpochMs = now - 5_000, nowEpochMs = now, ttlMs = 30_000),
        )
        assertEquals(
            PresenceStatus.UNKNOWN,
            effectivePresence(registered = true, observedAtEpochMs = now - 60_000, nowEpochMs = now, ttlMs = 30_000),
        )
        assertEquals(
            PresenceStatus.OFFLINE,
            effectivePresence(registered = false, observedAtEpochMs = now, nowEpochMs = now, ttlMs = 30_000),
        )
        assertEquals(
            PresenceStatus.UNKNOWN,
            effectivePresence(registered = true, observedAtEpochMs = null, nowEpochMs = now, ttlMs = 30_000),
        )
    }

    @Test
    fun `presence state expiry degrades to unknown`() {
        val state = PresenceState(PresenceStatus.ONLINE, observedAtEpochMs = 1_000, ttlMs = 10_000)
        assertEquals(PresenceStatus.ONLINE, state.effective(nowEpochMs = 11_000))
        assertEquals(PresenceStatus.UNKNOWN, state.effective(nowEpochMs = 10_001 + 10_000))
    }

    @Test
    fun `message content validation enforces byte bound`() {
        assertTrue(MessageLimits.validateContent("hello"))
        assertFalse(MessageLimits.validateContent(""))
        assertFalse(MessageLimits.validateContent("bad\u0000null"))
        assertFalse(MessageLimits.validateContent("x".repeat(MessageLimits.MAX_CONTENT_BYTES + 1)))
        val exactlyMax = "ä".repeat(MessageLimits.MAX_CONTENT_BYTES / 2) // 2 bytes each
        assertTrue(MessageLimits.validateContent(exactlyMax))
    }

    @Test
    fun `cache key prefers server id then client key`() {
        val serverKnown = Message(
            id = "srv-1", conversationId = "dm-alice-bob", senderId = "alice", recipientId = "bob",
            content = "hi", sentAtEpochMs = 0, clientKey = "k-1", status = MessageStatus.SENT,
        )
        assertEquals("srv-1", cacheKeyFor(serverKnown))
        val localOnly = serverKnown.copy(id = "")
        assertEquals("client:k-1", cacheKeyFor(localOnly))
    }
}
