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
        val event = assertIs<RealtimeEvent.Unknown>(parseRealtimeEvent("""{"type":"typing","payload":"{}"}"""))
        assertEquals("typing", event.type)
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
