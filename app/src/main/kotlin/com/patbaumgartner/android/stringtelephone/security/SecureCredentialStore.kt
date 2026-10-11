package com.patbaumgartner.android.stringtelephone.security

import android.content.Context

/** Credential storage boundary so previews and tests can fake the Keystore. */
interface CredentialStore {
    fun save(serverUrl: String, username: String, apiToken: String, sipSecret: String)
    fun serverUrl(): String?
    fun username(): String?
    fun apiToken(): String?
    fun sipSecret(): String?
    fun hasCredentials(): Boolean
    fun clear()
}

/** Encrypted-at-rest credential storage backed by the Android Keystore. */
class SecureCredentialStore(context: Context) : CredentialStore {

    private val prefs by lazy {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            "string_telephone_credentials",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun store(): android.content.SharedPreferences = prefs

    override fun save(serverUrl: String, username: String, apiToken: String, sipSecret: String) {
        store().edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_API_TOKEN, apiToken)
            .putString(KEY_SIP_SECRET, sipSecret)
            .apply()
    }

    override fun serverUrl(): String? = store().getString(KEY_SERVER_URL, null)
    override fun username(): String? = store().getString(KEY_USERNAME, null)
    override fun apiToken(): String? = store().getString(KEY_API_TOKEN, null)
    override fun sipSecret(): String? = store().getString(KEY_SIP_SECRET, null)

    override fun hasCredentials(): Boolean = serverUrl() != null && apiToken() != null

    /** Logout: removes every stored credential and cached session artifact. */
    override fun clear() {
        store().edit().clear().apply()
    }
}

private const val KEY_SERVER_URL = "server_url"
private const val KEY_USERNAME = "username"
private const val KEY_API_TOKEN = "api_token"
private const val KEY_SIP_SECRET = "sip_secret"
