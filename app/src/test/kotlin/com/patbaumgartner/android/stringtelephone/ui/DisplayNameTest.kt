package com.patbaumgartner.android.stringtelephone.ui

import androidx.test.core.app.ApplicationProvider
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers displayNameOf's fallback rules: a matching contact resolves to its
 * display name, a blank display name falls back to the username, and an
 * unknown peer shows the username itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.patbaumgartner.android.stringtelephone.ScreenshotTestApp::class)
class DisplayNameTest {

    private fun viewModelWith(contacts: List<ContactDto>): AppViewModel {
        val store = FakeCredentialStore()
        store.save(
            serverUrl = "https://phone.example.com",
            username = "alice",
            apiToken = "st_preview_token",
            sipSecret = "preview-sip-secret",
        )
        val container = com.patbaumgartner.android.stringtelephone.di.AppContainer(
            ApplicationProvider.getApplicationContext(),
            credentialStoreOverride = store,
            callEngineOverride = FakeCallEngine(),
            repositoryOverride = com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository(
                { null },
                com.patbaumgartner.android.stringtelephone.chat.ChatCache(),
            ),
        )
        container.repository.seedForPreviews(
            me = com.patbaumgartner.android.stringtelephone.network.MeResult("alice", "Alice"),
            contacts = contacts,
        )
        return AppViewModel(container)
    }

    @Test
    fun matchingContactResolvesDisplayName() {
        val viewModel = viewModelWith(listOf(ContactDto("bob", "Bob Builder", "online")))
        assertEquals(
            "Bob Builder",
            viewModel.displayNameOf("bob", viewModel.contactsState().value.contacts),
        )
    }

    @Test
    fun blankDisplayNameFallsBackToUsername() {
        val viewModel = viewModelWith(listOf(ContactDto("bob", "", "online")))
        assertEquals("bob", viewModel.displayNameOf("bob", viewModel.contactsState().value.contacts))
    }

    @Test
    fun unknownPeerFallsBackToUsername() {
        val viewModel = viewModelWith(
            listOf(ContactDto("bob", "Bob", "online")),
        )
        assertEquals("mallory", viewModel.displayNameOf("mallory", viewModel.contactsState().value.contacts))
    }

    @Test
    fun draftsStayWithTheirConversationAndAreRemovedByLocalDataClear() {
        val viewModel = viewModelWith(emptyList())
        viewModel.onChatInputChanged("bob", "Only for Bob")
        viewModel.openChat("carol")
        assertEquals("", viewModel.chatInput)
        viewModel.openChat("bob")
        assertEquals("Only for Bob", viewModel.chatInput)
        viewModel.clearLocalData()
        assertEquals("", viewModel.chatDraftFor("bob").value)
        assertEquals("", viewModel.chatInput)
    }
}
