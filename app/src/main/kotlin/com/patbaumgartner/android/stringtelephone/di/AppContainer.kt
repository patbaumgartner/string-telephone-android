package com.patbaumgartner.android.stringtelephone.di

import android.content.Context
import com.patbaumgartner.android.stringtelephone.call.CallEngine
import com.patbaumgartner.android.stringtelephone.call.LinphoneCallEngine
import com.patbaumgartner.android.stringtelephone.chat.ChatCache
import com.patbaumgartner.android.stringtelephone.data.StringTelephoneRepository
import com.patbaumgartner.android.stringtelephone.network.CompanionApiClient
import com.patbaumgartner.android.stringtelephone.network.RealtimeClient
import com.patbaumgartner.android.stringtelephone.security.CredentialStore
import com.patbaumgartner.android.stringtelephone.security.SecureCredentialStore
import com.patbaumgartner.android.stringtelephone.session.SessionController

/**
 * Manual dependency container built once in [StringTelephoneApp.onCreate].
 * No DI framework, matching the reference project's convention.
 */
class AppContainer(
    val context: Context,
    credentialStoreOverride: CredentialStore? = null,
    callEngineOverride: CallEngine? = null,
    repositoryOverride: StringTelephoneRepository? = null,
    val buildInfo: BuildInfo = BuildInfo.current,
) {
    val credentialStore: CredentialStore = credentialStoreOverride ?: SecureCredentialStore(context)
    val chatCache = ChatCache()

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun apiClient(): CompanionApiClient? {
        val url = credentialStore.serverUrl() ?: return null
        val token = credentialStore.apiToken() ?: return null
        return CompanionApiClient(url, { token }, http)
    }

    private fun realtimeClient(): RealtimeClient? {
        val url = credentialStore.serverUrl() ?: return null
        credentialStore.apiToken() ?: return null
        return RealtimeClient(
            baseUrl = url,
            tokenProvider = { credentialStore.apiToken() },
            http = http,
        )
    }

    val repository: StringTelephoneRepository by lazy {
        repositoryOverride ?: StringTelephoneRepository(
            apiProvider = { apiClient() },
            chatCache = chatCache,
            realtimeFactory = { realtimeClient() },
        )
    }

    val callEngine: CallEngine = callEngineOverride ?: LinphoneCallEngine(context)

    /** Process-wide session; outlives the Activity so calls and messages reach a closed app. */
    val session: SessionController by lazy {
        SessionController(context, credentialStore, repository, callEngine)
    }
}
