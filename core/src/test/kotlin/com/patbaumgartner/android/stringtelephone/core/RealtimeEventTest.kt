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
        assertEquals(null, event.replyToId)
    }

    @Test
    fun `a new-message frame carries the reply target when present`() {
        val frame = """
            {"type":"new-message","payload":"{\"id\":\"m2\",\"sender\":\"bob\",\"recipient\":\"alice\",\"content\":\"yes\",\"createdAt\":43,\"clientKey\":\"k2\",\"replyToId\":\"m1\"}"}
        """.trimIndent()
        val event = assertIs<RealtimeEvent.NewMessage>(parseRealtimeEvent(frame))
        assertEquals("m1", event.replyToId)
    }

    @Test
    fun `a new-message frame carries voice duration and waveform`() {
        val frame = """
            {"type":"new-message","payload":"{\"id\":\"m3\",\"sender\":\"bob\",\"recipient\":\"alice\",\"content\":\"\",\"createdAt\":44,\"clientKey\":\"k3\",\"type\":\"VOICE\",\"mediaId\":\"med1\",\"durationMs\":12345,\"waveform\":\"10,50,90\"}"}
        """.trimIndent()
        val event = assertIs<RealtimeEvent.NewMessage>(parseRealtimeEvent(frame))
        assertEquals("VOICE", event.type)
        assertEquals(12_345, event.durationMs)
        assertEquals("10,50,90", event.waveform)
    }

    @Test
    fun `parses a message-deleted envelope into an event`() {
        val frame = """
            {"type":"message-deleted","payload":"{\"messageId\":\"m1\",\"peer\":\"alice\",\"deletedBy\":\"bob\",\"deletedAt\":77}"}
        """.trimIndent()
        val event = assertIs<RealtimeEvent.MessageDeleted>(parseRealtimeEvent(frame))
        assertEquals("m1", event.messageId)
        assertEquals("alice", event.peer)
        assertEquals("bob", event.deletedBy)
        assertEquals(77, event.deletedAtEpochMs)
    }

    @Test
    fun `parses a message-edited envelope into an event`() {
        val frame = """
            {"type":"message-edited","payload":"{\"messageId\":\"m1\",\"peer\":\"alice\",\"content\":\"hello\",\"editedAt\":99}"}
        """.trimIndent()
        val event = assertIs<RealtimeEvent.MessageEdited>(parseRealtimeEvent(frame))
        assertEquals("m1", event.messageId)
        assertEquals("alice", event.peer)
        assertEquals("hello", event.content)
        assertEquals(99, event.editedAtEpochMs)
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
    fun `parses group frames into their events`() {
        val message = parseRealtimeEvent(
            """{"type":"group-message","payload":"{\"id\":\"g1\",\"sender\":\"alice\",\"recipient\":\"\",\"groupId\":\"grp\",\"content\":\"hi all\",\"createdAt\":42,\"clientKey\":\"k1\"}"}""",
        )
        val groupMessage = assertIs<RealtimeEvent.GroupMessage>(message)
        assertEquals("g1", groupMessage.id)
        assertEquals("alice", groupMessage.sender)
        assertEquals("grp", groupMessage.groupId)
        assertEquals("hi all", groupMessage.content)

        val read = assertIs<RealtimeEvent.GroupMessageRead>(
            parseRealtimeEvent("""{"type":"group-message-read","payload":"{\"groupId\":\"grp\",\"reader\":\"bob\",\"messageIds\":[\"g1\"],\"readAt\":99}"}"""),
        )
        assertEquals("grp", read.groupId)
        assertEquals("bob", read.reader)
        assertEquals(listOf("g1"), read.messageIds)

        val reaction = assertIs<RealtimeEvent.GroupMessageReaction>(
            parseRealtimeEvent("""{"type":"group-reaction","payload":"{\"messageId\":\"g1\",\"peer\":\"\",\"groupId\":\"grp\",\"authorUsername\":\"bob\",\"emoji\":\"👍\",\"action\":\"added\"}"}"""),
        )
        assertEquals("grp", reaction.groupId)
        assertEquals("bob", reaction.authorUsername)
        assertEquals(true, reaction.added)

        val deleted = assertIs<RealtimeEvent.GroupMessageDeleted>(
            parseRealtimeEvent("""{"type":"group-deleted","payload":"{\"messageId\":\"g1\",\"peer\":\"\",\"groupId\":\"grp\",\"deletedBy\":\"alice\",\"deletedAt\":77}"}"""),
        )
        assertEquals("grp", deleted.groupId)
        assertEquals("alice", deleted.deletedBy)

        // The group typing variant rides the same envelope with a groupId.
        val typing = assertIs<RealtimeEvent.GroupTyping>(
            parseRealtimeEvent("""{"type":"typing","payload":"{\"peer\":\"bob\",\"typing\":true,\"groupId\":\"grp\"}"}"""),
        )
        assertEquals("grp", typing.groupId)
        assertEquals("bob", typing.from)
        assertEquals(true, typing.typing)
    }

    @Test
    fun `group frames without a groupId are dropped instead of misrouted`() {
        assertNull(parseRealtimeEvent("""{"type":"group-message","payload":"{\"id\":\"g1\",\"sender\":\"a\",\"recipient\":\"\",\"content\":\"x\",\"createdAt\":1,\"clientKey\":\"k\"}"}"""))
        assertNull(parseRealtimeEvent("""{"type":"group-reaction","payload":"{\"messageId\":\"m1\",\"peer\":\"\",\"authorUsername\":\"a\",\"emoji\":\"x\",\"action\":\"added\"}"}"""))
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
