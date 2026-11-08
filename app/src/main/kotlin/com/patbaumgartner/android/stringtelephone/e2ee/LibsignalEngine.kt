package com.patbaumgartner.android.stringtelephone.e2ee

import com.patbaumgartner.android.stringtelephone.core.KeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.KyberPreKeyDto
import com.patbaumgartner.android.stringtelephone.core.OneTimePreKeyDto
import com.patbaumgartner.android.stringtelephone.core.PeerKeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.SignedPreKeyDto
import com.patbaumgartner.android.stringtelephone.core.e2ee.E2eeEngine
import com.patbaumgartner.android.stringtelephone.core.e2ee.Envelope
import com.patbaumgartner.android.stringtelephone.core.e2ee.SafetyNumber
import com.patbaumgartner.android.stringtelephone.security.KeyValueStore
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.Base64
import java.util.UUID

/**
 * Persistent [SignalProtocolStore] over the encrypted [KeyValueStore]
 * (ADR-0015, Phase 22). Every libsignal record is serialized to bytes and
 * stored base64 under a namespaced key, so identity keys, prekeys and
 * ratchet sessions survive process death and sign-out is the only way to
 * wipe them. Group sender keys are stored too (the interface requires it)
 * even though Phase 22 uses pairwise sessions only — groups are per-member
 * fan-out, no sender keys.
 */
class PersistentSignalProtocolStore(private val kv: KeyValueStore) : SignalProtocolStore {

    private val b64 = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    private fun addrKey(prefix: String, address: SignalProtocolAddress) =
        "$prefix.${address.name}.${address.deviceId}"

    // ── Identity ────────────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val stored = kv.get(KEY_IDENTITY) ?: return IdentityKeyPair.generate().also {
            kv.put(KEY_IDENTITY, b64.encodeToString(it.serialize()))
            kv.put(KEY_REGISTRATION_ID, KeyHelper.generateRegistrationId(false).toString())
        }
        return IdentityKeyPair(dec.decode(stored))
    }

    override fun getLocalRegistrationId(): Int =
        kv.get(KEY_REGISTRATION_ID)?.toIntOrNull()
            ?: KeyHelper.generateRegistrationId(false).also { kv.put(KEY_REGISTRATION_ID, it.toString()) }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = addrKey(KEY_TRUSTED_IDENTITY, address)
        val existing = kv.get(key)
        kv.put(key, b64.encodeToString(identityKey.serialize()))
        return if (existing != null && existing != b64.encodeToString(identityKey.serialize()))
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val existing = kv.get(addrKey(KEY_TRUSTED_IDENTITY, address)) ?: return true // trust on first use
        return existing == b64.encodeToString(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey {
        val stored = kv.get(addrKey(KEY_TRUSTED_IDENTITY, address))
            ?: throw InvalidKeyException("no identity stored for ${address.name}")
        return IdentityKey(dec.decode(stored))
    }

    // ── One-time prekeys ────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val stored = kv.get("$KEY_PREKEY.$preKeyId")
            ?: throw InvalidKeyIdException("no prekey $preKeyId")
        return PreKeyRecord(dec.decode(stored))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        kv.put("$KEY_PREKEY.$preKeyId", b64.encodeToString(record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = kv.get("$KEY_PREKEY.$preKeyId") != null

    override fun removePreKey(preKeyId: Int) {
        kv.remove("$KEY_PREKEY.$preKeyId")
    }

    // ── Signed prekeys ──────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val stored = kv.get("$KEY_SIGNED_PREKEY.$signedPreKeyId")
            ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
        return SignedPreKeyRecord(dec.decode(stored))
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        kv.keys().filter { it.startsWith("$KEY_SIGNED_PREKEY.") }
            .map { SignedPreKeyRecord(dec.decode(kv.get(it)!!)) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        kv.put("$KEY_SIGNED_PREKEY.$signedPreKeyId", b64.encodeToString(record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        kv.get("$KEY_SIGNED_PREKEY.$signedPreKeyId") != null

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        kv.remove("$KEY_SIGNED_PREKEY.$signedPreKeyId")
    }

    // ── Kyber (PQXDH last-resort) prekeys ───────────────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val stored = kv.get("$KEY_KYBER_PREKEY.$kyberPreKeyId")
            ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
        return KyberPreKeyRecord(dec.decode(stored))
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        kv.keys().filter { it.startsWith("$KEY_KYBER_PREKEY.") }
            .map { KyberPreKeyRecord(dec.decode(kv.get(it)!!)) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        kv.put("$KEY_KYBER_PREKEY.$kyberPreKeyId", b64.encodeToString(record.serialize()))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        kv.get("$KEY_KYBER_PREKEY.$kyberPreKeyId") != null

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        // Record the (kyber, signed, base) tuple so a replayed handshake is
        // detected instead of silently reusing the last-resort key.
        kv.put(
            "$KEY_KYBER_USED.$kyberPreKeyId.$signedPreKeyId.${b64.encodeToString(baseKey.serialize())}",
            "1",
        )
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val stored = kv.get(addrKey(KEY_SESSION, address)) ?: return SessionRecord()
        return SessionRecord(dec.decode(stored))
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.filter { containsSession(it) }.map { loadSession(it) }

    override fun getSubDeviceSessions(name: String): List<Int> =
        kv.keys()
            .filter { it.startsWith("$KEY_SESSION.$name.") }
            .mapNotNull { it.substringAfterLast('.').toIntOrNull() }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        kv.put(addrKey(KEY_SESSION, address), b64.encodeToString(record.serialize()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        kv.get(addrKey(KEY_SESSION, address)) != null && loadSession(address).hasSenderChain()

    override fun deleteSession(address: SignalProtocolAddress) {
        kv.remove(addrKey(KEY_SESSION, address))
    }

    override fun deleteAllSessions(name: String) {
        kv.keys().filter { it.startsWith("$KEY_SESSION.$name.") }.forEach { kv.remove(it) }
    }

    // ── Group sender keys (unused in Phase 22 — pairwise fan-out only) ──────

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        kv.put(
            "$KEY_SENDER_KEY.${sender.name}.${sender.deviceId}.$distributionId",
            b64.encodeToString(record.serialize()),
        )
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val stored = kv.get("$KEY_SENDER_KEY.${sender.name}.${sender.deviceId}.$distributionId") ?: return null
        return SenderKeyRecord(dec.decode(stored))
    }

    private companion object {
        const val KEY_IDENTITY = "e2ee.identity"
        const val KEY_REGISTRATION_ID = "e2ee.registrationId"
        const val KEY_TRUSTED_IDENTITY = "e2ee.trustedIdentity"
        const val KEY_PREKEY = "e2ee.prekey"
        const val KEY_SIGNED_PREKEY = "e2ee.signedPrekey"
        const val KEY_KYBER_PREKEY = "e2ee.kyberPrekey"
        const val KEY_KYBER_USED = "e2ee.kyberUsed"
        const val KEY_SESSION = "e2ee.session"
        const val KEY_SENDER_KEY = "e2ee.senderKey"
    }
}

/**
 * The production [E2eeEngine] (ADR-0015, Phase 22): libsignal's PQXDH +
 * Double Ratchet over the [PersistentSignalProtocolStore]. Text messages are
 * encrypted per recipient; the server only relays the opaque envelopes.
 * Mirrors the JVM reference implementation proven in `:core:test`
 * (`LibsignalJvmEngine`), with all private state persisted encrypted at rest.
 */
class LibsignalEngine(
    private val kv: KeyValueStore,
    private val store: PersistentSignalProtocolStore = PersistentSignalProtocolStore(kv),
) : E2eeEngine {

    private val b64 = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    /** Peers whose identity key changed since last trusted; surfaced as a warning (ADR-0015 §3). */
    private val changedIdentities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var nextPreKeyId: Int
        get() = kv.get(KEY_NEXT_PREKEY)?.toIntOrNull() ?: 1
        set(value) { kv.put(KEY_NEXT_PREKEY, value.toString()) }
    private var nextSignedPreKeyId: Int
        get() = kv.get(KEY_NEXT_SIGNED)?.toIntOrNull() ?: 1
        set(value) { kv.put(KEY_NEXT_SIGNED, value.toString()) }
    private var nextKyberPreKeyId: Int
        get() = kv.get(KEY_NEXT_KYBER)?.toIntOrNull() ?: 1
        set(value) { kv.put(KEY_NEXT_KYBER, value.toString()) }

    private fun address(peer: String) = SignalProtocolAddress(peer, DEVICE_ID)

    override val identityKeyPublicBase64: String
        get() = b64.encodeToString(store.identityKeyPair.publicKey.serialize())

    override val registrationId: Int
        get() = store.localRegistrationId

    override fun hasSession(peer: String): Boolean = store.containsSession(address(peer))

    override fun buildKeyBundle(oneTimePreKeyCount: Int): KeyBundleDto {
        // Signed and Kyber prekeys are signed by the *identity* private key —
        // that is what SessionBuilder verifies on the receiving side.
        val identityPrivate = store.identityKeyPair.privateKey

        val signedId = nextSignedPreKeyId++
        val signedPair = ECKeyPair.generate()
        val signedSig = identityPrivate.calculateSignature(signedPair.publicKey.serialize())
        store.storeSignedPreKey(signedId, SignedPreKeyRecord(signedId, System.currentTimeMillis(), signedPair, signedSig))

        val kyberId = nextKyberPreKeyId++
        val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identityPrivate.calculateSignature(kyberPair.publicKey.serialize())
        store.storeKyberPreKey(kyberId, KyberPreKeyRecord(kyberId, System.currentTimeMillis(), kyberPair, kyberSig))

        val oneTime = (1..oneTimePreKeyCount).map {
            val id = nextPreKeyId++
            val pair = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, pair))
            OneTimePreKeyDto(id, b64.encodeToString(pair.publicKey.serialize()))
        }
        return KeyBundleDto(
            registrationId = registrationId,
            identityKey = identityKeyPublicBase64,
            signedPreKey = SignedPreKeyDto(signedId, b64.encodeToString(signedPair.publicKey.serialize()), b64.encodeToString(signedSig)),
            kyberPreKey = KyberPreKeyDto(kyberId, b64.encodeToString(kyberPair.publicKey.serialize()), b64.encodeToString(kyberSig)),
            oneTimePreKeys = oneTime,
        )
    }

    override fun processPeerBundle(peer: String, bundle: PeerKeyBundleDto, oneTimePreKey: OneTimePreKeyDto?): Result<Unit> = runCatching {
        val identityKey = IdentityKey(dec.decode(bundle.identityKey))
        val preKeyPublic = oneTimePreKey?.let { ECPublicKey(dec.decode(it.publicKey)) }
        val preKeyBundle = PreKeyBundle(
            registrationId = bundle.registrationId,
            deviceId = DEVICE_ID,
            preKeyId = oneTimePreKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            preKeyPublic = preKeyPublic,
            signedPreKeyId = bundle.signedPreKey.id,
            signedPreKeyPublic = ECPublicKey(dec.decode(bundle.signedPreKey.publicKey)),
            signedPreKeySignature = dec.decode(bundle.signedPreKey.signature),
            identityKey = identityKey,
            kyberPreKeyId = bundle.kyberPreKey.id,
            kyberPreKeyPublic = KEMPublicKey(dec.decode(bundle.kyberPreKey.publicKey)),
            kyberPreKeySignature = dec.decode(bundle.kyberPreKey.signature),
        )
        SessionBuilder(store, address(peer)).process(preKeyBundle)
        store.saveIdentity(address(peer), identityKey)
    }

    override fun encrypt(peer: String, plaintext: String): Result<String> = runCatching {
        check(hasSession(peer)) { "no session with $peer — process their key bundle first" }
        val message = SessionCipher(store, address(peer)).encrypt(plaintext.toByteArray(Charsets.UTF_8))
        Envelope.format(message.type, message.serialize())
    }

    override fun decrypt(peer: String, envelope: String): String? {
        val (type, bytes) = Envelope.parse(envelope) ?: return null
        if (type == CiphertextMessage.WHISPER_TYPE && !hasSession(peer)) return null
        val cipher = SessionCipher(store, address(peer))
        return try {
            when (type) {
                CiphertextMessage.PREKEY_TYPE -> String(cipher.decrypt(PreKeySignalMessage(bytes)), Charsets.UTF_8)
                CiphertextMessage.WHISPER_TYPE -> String(cipher.decrypt(SignalMessage(bytes)), Charsets.UTF_8)
                else -> null
            }
        } catch (e: UntrustedIdentityException) {
            // A changed identity key must never silently decrypt: flag it so
            // the chat shows the safety-number warning (ADR-0015 §3).
            changedIdentities.add(peer)
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun isIdentityTrusted(peer: String, identityKeyBase64: String): Boolean {
        val stored = kv.get("e2ee.trustedIdentity.$peer.$DEVICE_ID") ?: return true
        return stored == identityKeyBase64
    }

    override fun trustIdentity(peer: String, identityKeyBase64: String) {
        kv.put("e2ee.trustedIdentity.$peer.$DEVICE_ID", identityKeyBase64)
    }

    override fun safetyNumber(peer: String): String? {
        val remote = kv.get("e2ee.trustedIdentity.$peer.$DEVICE_ID") ?: return null
        return SafetyNumber.of(identityKeyPublicBase64, remote)
    }

    override fun identityChanged(peer: String): Boolean = peer in changedIdentities

    override fun clearIdentityChange(peer: String) {
        changedIdentities.remove(peer)
    }

    private companion object {
        const val DEVICE_ID = 1
        const val KEY_NEXT_PREKEY = "e2ee.nextPreKeyId"
        const val KEY_NEXT_SIGNED = "e2ee.nextSignedPreKeyId"
        const val KEY_NEXT_KYBER = "e2ee.nextKyberPreKeyId"
    }
}
