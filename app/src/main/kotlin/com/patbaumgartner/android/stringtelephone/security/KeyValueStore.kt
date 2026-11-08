package com.patbaumgartner.android.stringtelephone.security

import android.content.Context

/**
 * Minimal string key-value boundary so previews and tests can fake the
 * Keystore-backed store. The E2EE protocol store (ADR-0015, Phase 22)
 * persists its libsignal records through this interface — never through
 * plain SharedPreferences.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    /** All keys currently present (used to enumerate e.g. prekey ids). */
    fun keys(): Set<String>
    fun clear()
}

/**
 * Encrypted-at-rest key-value storage backed by the Android Keystore, in its
 * own preferences file so the credential store and the protocol store stay
 * separable (logout clears credentials; wiping keys is a deliberate act).
 */
class SecureKeyValueStore(context: Context, prefsName: String) : KeyValueStore {

    private val prefs by lazy {
        val masterKey = androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)
            .build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
    override fun clear() = prefs.edit().clear().apply()
}
