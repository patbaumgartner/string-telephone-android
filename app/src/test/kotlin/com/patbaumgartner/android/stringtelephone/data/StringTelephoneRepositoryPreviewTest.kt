package com.patbaumgartner.android.stringtelephone.data

import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.network.MeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conversation-list previews and search (Phase 15): the repository mirrors
 * the newest cached message per peer into an observable map, and the contact
 * filter narrows the list locally.
 */
class StringTelephoneRepositoryPreviewTest {

    private fun repository(cache: ChatCache = ChatCache()): StringTelephoneRepository =
        StringTelephoneRepository(apiProvider = { null }, chatCache = cache)

    private suspend fun awaitPreviews(repo: StringTelephoneRepository, peer: String) = withTimeout(5_000) {
        while (repo.previews.value[peer] == null) delay(10)
        repo.previews.value[peer]
    }

    @Test
    fun `the newest cached message becomes the peer preview`() = runBlocking {
        val cache = ChatCache()
        val repo = repository(cache)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())

        cache.append("bob", ChatMessage(null, "k1", fromMe = false, "hello there", 1_000, SendState.SENT))

        val preview = awaitPreviews(repo, "bob")
        assertEquals("hello there", preview?.content)
    }

    @Test
    fun `previews follow state changes of the newest message`() = runBlocking {
        val cache = ChatCache()
        val repo = repository(cache)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())

        cache.append("bob", ChatMessage(null, "k1", fromMe = true, "from the composer", 1_000, SendState.PENDING))
        awaitPreviews(repo, "bob")

        cache.markSent("k1", "srv-9")
        withTimeout(5_000) {
            while (repo.previews.value["bob"]?.state != SendState.SENT) delay(10)
        }
        assertEquals(SendState.SENT, repo.previews.value["bob"]?.state)
    }

    @Test
    fun `signing out clears all previews`() = runBlocking {
        val cache = ChatCache()
        val repo = repository(cache)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        cache.append("bob", ChatMessage(null, "k1", fromMe = false, "hello", 1_000, SendState.SENT))
        awaitPreviews(repo, "bob")

        repo.clearSession()

        assertTrue(repo.previews.value.isEmpty())
    }

    @Test
    fun `hiding the last message removes the peer preview`() = runBlocking {
        val cache = ChatCache()
        val repo = repository(cache)
        repo.seedForPreviews(MeResult("alice", "Alice"), emptyList())
        val message = ChatMessage(null, "k1", fromMe = false, "hello", 1_000, SendState.SENT)
        cache.append("bob", message)
        awaitPreviews(repo, "bob")

        cache.hideLocally("bob", "k1")

        withTimeout(5_000) {
            while (repo.previews.value.containsKey("bob")) delay(10)
        }
        assertTrue(repo.previews.value.isEmpty())
    }

    @Test
    fun `contact filter matches display name and username case-insensitively`() {
        val repo = repository()
        val contacts = listOf(
            ContactDto("bob", "Bob", "online"),
            ContactDto("carol", "Carol Diaz", "offline"),
            ContactDto("dave", "Dave", "unknown"),
        )

        assertEquals(listOf("Bob", "Carol Diaz", "Dave"), repo.filterContacts(contacts, "").map { it.displayName })
        assertEquals(listOf("Bob"), repo.filterContacts(contacts, "bo").map { it.displayName })
        assertEquals(listOf("Carol Diaz"), repo.filterContacts(contacts, "diaz").map { it.displayName })
        assertEquals(listOf("Dave"), repo.filterContacts(contacts, "DAVE").map { it.displayName })
        assertTrue(repo.filterContacts(contacts, "zzz").isEmpty())
        assertFalse(repo.filterContacts(contacts, "o").isEmpty())
    }

    @Test
    fun `blank queries keep the full list`() {
        val repo = repository()
        val contacts = listOf(ContactDto("bob", "Bob", "online"), ContactDto("carol", "Carol", "offline"))
        assertEquals(contacts, repo.filterContacts(contacts, "  "))
    }
}
