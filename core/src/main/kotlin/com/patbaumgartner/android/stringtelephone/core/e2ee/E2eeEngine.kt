package com.patbaumgartner.android.stringtelephone.core.e2ee

import com.patbaumgartner.android.stringtelephone.core.KeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.OneTimePreKeyDto
import com.patbaumgartner.android.stringtelephone.core.PeerKeyBundleDto

/**
 * The end-to-end encryption seam (ADR-0015, Phase 22). Core and the app talk
 * to this interface only; the single production implementation lives in the
 * app module and binds libsignal (GPL-3.0, ADR-0002), while tests bind the
 * JVM reference implementation of the same interface. Text messages are
 * encrypted per recipient with a pairwise Double Ratchet session; the server
 * relays opaque envelopes and never sees plaintext.
 */
interface E2eeEngine {
    /** This device's identity public key, base64 (libsignal serialization, version byte included). */
    val identityKeyPublicBase64: String

    /** This device's libsignal registration id. */
    val registrationId: Int

    /** True when a Double Ratchet session with [peer] already exists locally. */
    fun hasSession(peer: String): Boolean

    /**
     * Builds the publishable key bundle for `PUT /v1/keys`: the current
     * signed prekey, the Kyber last-resort prekey (PQXDH) and a fresh batch
     * of [oneTimePreKeyCount] one-time prekeys. Already-published one-time
     * prekey ids are ignored server-side, so re-publishing is a top-up.
     */
    fun buildKeyBundle(oneTimePreKeyCount: Int = 50): KeyBundleDto

    /**
     * Establishes (or refreshes) a session with [peer] from their published
     * [bundle] plus one consumed one-time prekey when [oneTimePreKey] is
     * present (PQXDH falls back to the Kyber prekey alone otherwise).
     */
    fun processPeerBundle(peer: String, bundle: PeerKeyBundleDto, oneTimePreKey: OneTimePreKeyDto?): Result<Unit>

    /**
     * Encrypts [plaintext] for [peer] and returns the wire envelope
     * `"<type>:<base64>"` (see [Envelope]). Fails when no session exists —
     * the caller must [processPeerBundle] first.
     */
    fun encrypt(peer: String, plaintext: String): Result<String>

    /**
     * Decrypts a wire [envelope] from [peer] back to plaintext; null when the
     * envelope is malformed, the session is missing, or the ciphertext fails
     * verification (a bad MAC never returns garbage).
     */
    fun decrypt(peer: String, envelope: String): String?

    /** True when the stored identity key for [peer] still equals [identityKeyBase64]. */
    fun isIdentityTrusted(peer: String, identityKeyBase64: String): Boolean

    /** Records [identityKeyBase64] as the trusted identity key of [peer]. */
    fun trustIdentity(peer: String, identityKeyBase64: String)

    /**
     * The safety number for [peer] (60 digits, grouped by 5) computed from
     * this device's identity key and the stored remote identity key; null
     * when no identity is known for [peer] yet.
     */
    fun safetyNumber(peer: String): String?

    /**
     * True when the peer's identity key changed since it was last trusted —
     * libsignal refuses to decrypt under a changed key
     * (`UntrustedIdentityException`), so the app surfaces a safety-number
     * warning banner instead of silently failing (ADR-0015 §3).
     */
    fun identityChanged(peer: String): Boolean

    /** Clears the identity-change warning for [peer] after the user compared safety numbers. */
    fun clearIdentityChange(peer: String)
}

/**
 * Wire shape of one ciphertext envelope: `"<type>:<base64>"` where type is
 * 2 (WHISPER_TYPE, an established ratchet message) or 3 (PREKEY_TYPE, the
 * first message of a fresh session). This mirrors the server's validation
 * exactly (`Messages.ENVELOPE_PATTERN` / `MAX_ENVELOPE_CHARS`, ADR-0015) so a
 * client-built envelope is always accepted by the relay.
 */
object Envelope {
    /** Maximum envelope length in characters, identical to the server's cap. */
    const val MAX_CHARS = 16384

    private val pattern = Regex("^[23]:[A-Za-z0-9+/]+={0,2}$")

    /** True when [envelope] is a well-formed, size-capped wire envelope. */
    fun isValid(envelope: String): Boolean =
        envelope.length <= MAX_CHARS && pattern.matches(envelope)

    /** Formats [ciphertext] with [type] (2 or 3) as a wire envelope. */
    fun format(type: Int, ciphertext: ByteArray): String =
        "$type:${java.util.Base64.getEncoder().encodeToString(ciphertext)}"

    /** Splits a wire [envelope] into its type and raw ciphertext; null when malformed. */
    fun parse(envelope: String): Pair<Int, ByteArray>? = runCatching {
        require(pattern.matches(envelope)) { "not a wire envelope" }
        val colon = envelope.indexOf(':')
        val type = envelope.substring(0, colon).toInt()
        val bytes = java.util.Base64.getDecoder().decode(envelope.substring(colon + 1))
        type to bytes
    }.getOrNull()
}
