package com.patbaumgartner.android.stringtelephone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RealtimeEventTest {

    @Test
    fun `parses a new-message envelope into an event`() {
        val frame = """
            {"type":"new-message","payload":"{\"id\":\"m1\",\"sender\":\"alice\",\"recipient\":\"bob\",\"content\":\"hi\",\"createdAt\":42,\"clientKey\":\"k1\"}"}
        """.trimIndent()
        val event = assertIs<RealtimeEvent.NewMessage>(parseRealtimeEvent(frame))
        assertEquals("m1", event.id)
        assertEquals("alice", event.sender)
        assertEquals("bob", event.recipient)
        assertEquals("hi", event.content)
        assertEquals(42, event.createdAtEpochMs)
        assertEquals("k1", event.clientKey)
    }

    @Test
    fun `parses a presence envelope`() {
        val frame = """{"type":"presence","payload":"{\"username\":\"bob\",\"presence\":\"online\",\"updatedAt\":7}"}"""
        assertEquals(RealtimeEvent.Presence("bob", "online", 7), parseRealtimeEvent(frame))
        assertNull(parseRealtimeEvent("""{"type":"presence","payload":"{}"}"""))
    }

    @Test
    fun `reports unknown event types instead of dropping them silently`() {
        val event = assertIs<RealtimeEvent.Unknown>(parseRealtimeEvent("""{"type":"unknown-future","payload":"{}"}"""))
        assertEquals("unknown-future", event.type)
    }

    @Test
    fun `parses a typing envelope into an indicator`() {
        val start = parseRealtimeEvent("""{"type":"typing","payload":"{\"peer\":\"alice\",\"typing\":true}"}""")
        val startEvent = assertIs<RealtimeEvent.Typing>(start)
        assertEquals("alice", startEvent.peer)
        assertEquals(true, startEvent.typing)

        val stop = parseRealtimeEvent("""{"type":"typing","payload":"{\"peer\":\"alice\",\"typing\":false}"}""")
        val stopEvent = assertIs<RealtimeEvent.Typing>(stop)
        assertEquals("alice", stopEvent.peer)
        assertEquals(false, stopEvent.typing)

        // Missing fields or broken payloads are dropped, not reported.
        assertNull(parseRealtimeEvent("""{"type":"typing","payload":"{}"}"""))
        assertNull(parseRealtimeEvent("""{"type":"typing","payload":"{broken"}"""))
    }

        @Test
    fun `parses a message-reaction envelope into an event`() {
        val added = parseRealtimeEvent("""{"type":"message-reaction","payload":"{\"messageId\":\"m1\",\"peer\":\"alice\",\"authorUsername\":\"bob\",\"emoji\":\"👍\",\"action\":\"added\"}"}""")
        val addedEvent = assertIs<RealtimeEvent.MessageReaction>(added)
        assertEquals("m1", addedEvent.messageId)
        assertEquals("alice", addedEvent.peer)
        assertEquals("bob", addedEvent.authorUsername)
        assertEquals("👍", addedEvent.emoji)
        assertEquals(true, addedEvent.added)

        val removed = parseRealtimeEvent("""{"type":"message-reaction","payload":"{\"messageId\":\"m2\",\"peer\":\"bob\",\"authorUsername\":\"alice\",\"emoji\":\"😂\",\"action\":\"removed\"}"}""")
        val removedEvent = assertIs<RealtimeEvent.MessageReaction>(removed)
        assertEquals("m2", removedEvent.messageId)
        assertEquals("bob", removedEvent.peer)
        assertEquals("alice", removedEvent.authorUsername)
        assertEquals("😂", removedEvent.emoji)
        assertEquals(false, removedEvent.added)

        // Malformed payloads are dropped.
        assertNull(parseRealtimeEvent("""{"type":"message-reaction","payload":"{broken"}"""))
        assertNull(parseRealtimeEvent("""{"type":"message-reaction","payload":"{}"}"""))
    }

    @Test
    fun `parses a message-read envelope into a receipt`() {
        val frame = """{"type":"message-read","payload":"{\"messageIds\":[\"m1\",\"m2\"],\"readAt\":99}"}"""
        val event = assertIs<RealtimeEvent.MessageRead>(parseRealtimeEvent(frame))
        assertEquals(listOf("m1", "m2"), event.messageIds)
        assertEquals(99L, event.readAtEpochMs)
        assertNull(parseRealtimeEvent("""{"type":"message-read","payload":"{broken"}"""))
    }

    @Test
    fun `drops malformed frames and unknown fields`() {
        assertNull(parseRealtimeEvent("not json"))
        assertNull(parseRealtimeEvent("""{"type":"new-message","payload":"{broken"}"""))
        val event = assertIs<RealtimeEvent.NewMessage>(
            parseRealtimeEvent(
                """{"type":"new-message","extra":1,"payload":"{\"id\":\"m2\",\"sender\":\"a\",\"recipient\":\"b\",\"content\":\"c\",\"createdAt\":1,\"clientKey\":\"k\",\"future\":true}"}""",
            ),
        )
        assertEquals("m2", event.id)
    }
}
