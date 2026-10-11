package com.patbaumgartner.android.stringtelephone

import android.content.Context
import com.patbaumgartner.android.stringtelephone.di.AppContainer
import com.patbaumgartner.android.stringtelephone.ui.FakeCredentialStore

/**
 * Application used by Robolectric screenshot tests: wires fake credential
 * storage so container creation never touches EncryptedSharedPreferences.
 */
class ScreenshotTestApp : StringTelephoneApp() {

    override fun createContainer(): AppContainer {
        val store = FakeCredentialStore()
        store.save(
            serverUrl = "https://phone.example.com",
            username = "alice",
            apiToken = "st_preview_token",
            sipSecret = "preview-sip-secret",
        )
        return AppContainer(this, credentialStoreOverride = store)
    }
}
