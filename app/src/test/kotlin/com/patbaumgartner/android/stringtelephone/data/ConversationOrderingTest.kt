package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ConversationStateDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 18 list rules (framework-free): pinned float to the top, archived
 * move to the "Archived" section, relative order inside a group is kept,
 * and absent state reads as all-false.
 */
class ConversationOrderingTest {

    private fun contact(username: String) = ContactDto(username, username.replaceFirstChar { it.uppercase() }, "offline")

    /** Ordering is a pure function; the repository's network seams stay null. */
    private fun ordered(contacts: List<ContactDto>, states: Map<String, ConversationStateDto>) =
        StringTelephoneRepository({ null }, chatCache = com.patbaumgartner.android.stringtelephone.chat.ChatCache())
            .orderedContacts(contacts, states)

    @Test
    fun `pinned conversations float to the top preserving relative order`() {
        val contacts = listOf(contact("alice"), contact("bob"), contact("carol"), contact("dave"))
        val states = mapOf(
            "carol" to ConversationStateDto(pinned = true),
            "alice" to ConversationStateDto(pinned = true),
        )
        val (visible, archived) = ordered(contacts, states)
        assertTrue(archived.isEmpty())
        // Pinned first, in list order (the pinned group preserves the
        // incoming order).
        assertEquals(listOf("alice", "carol", "bob", "dave"), visible.map { it.username })
    }

    @Test
    fun `archived conversations leave the main list into the archived section`() {
        val contacts = listOf(contact("alice"), contact("bob"), contact("carol"))
        val states = mapOf(
            "bob" to ConversationStateDto(archived = true),
        )
        val (visible, archived) = ordered(contacts, states)
        assertEquals(listOf("alice", "carol"), visible.map { it.username })
        assertEquals(listOf("bob"), archived.map { it.username })
    }

    @Test
    fun `absent state reads as all-false and keeps everything visible`() {
        val contacts = listOf(contact("alice"), contact("bob"))
        val (visible, archived) = ordered(contacts, emptyMap())
        assertEquals(listOf("alice", "bob"), visible.map { it.username })
        assertTrue(archived.isEmpty())
    }

    @Test
    fun `pinned and archived combine to pinned top then rest with archived out`() {
        val contacts = listOf(contact("a"), contact("b"), contact("c"), contact("d"))
        val states = mapOf(
            "d" to ConversationStateDto(pinned = true, archived = true),
            "a" to ConversationStateDto(pinned = true),
        )
        val (visible, archived) = ordered(contacts, states)
        assertEquals(listOf("a", "b", "c"), visible.map { it.username })
        assertEquals(listOf("d"), archived.map { it.username })
    }
}
