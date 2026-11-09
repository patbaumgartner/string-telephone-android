package com.patbaumgartner.android.stringtelephone.core.e2ee

import com.patbaumgartner.android.stringtelephone.core.KeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.OneTimePreKeyDto
import com.patbaumgartner.android.stringtelephone.core.PeerKeyBundleDto
import com.patbaumgartner.android.stringtelephone.core.SignedPreKeyDto
import com.patbaumgartner.android.stringtelephone.core.KyberPreKeyDto
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM reference implementation of [E2eeEngine] over libsignal's
 * [InMemorySignalProtocolStore] (ADR-0015, Phase 22). It exists so the real
 * Signal-protocol crypto (PQXDH + Double Ratchet) is proven on the host JVM
 * in `:core:test`; the Android app binds the same protocol via the AAR in
 * `app/.../e2ee/LibsignalEngine.kt`.
 */
class LibsignalJvmEngine(
    val store: InMemorySignalProtocolStore = InMemorySignalProtocolStore(
        IdentityKeyPair.generate(),
        KeyHelper.generateRegistrationId(false),
    ),
) : E2eeEngine {
    private val b64 = Base64.getEncoder()
    private val trusted = mutableMapOf<String, String>() // peer -> identity key base64
    private val changedIdentities = mutableSetOf<String>()
    private var nextPreKeyId = 1
    private var nextSignedPreKeyId = 1
    private var nextKyberPreKeyId = 1

    private fun address(peer: String) = SignalProtocolAddress(peer, 1)

    override val identityKeyPublicBase64: String
        get() = b64.encodeToString(store.identityKeyPair.publicKey.serialize())

    override val registrationId: Int
        get() = store.localRegistrationId

    override fun hasSession(peer: String): Boolean = store.containsSession(address(peer))

    override fun buildKeyBundle(oneTimePreKeyCount: Int): KeyBundleDto {
        // Both the signed prekey and the Kyber prekey are signed by the
        // *identity* private key — that is what SessionBuilder verifies.
        val identityPrivate = store.identityKeyPair.privateKey
        val signedId = nextSignedPreKeyId++
        val signedPair = ECKeyPair.generate()
        val signedSig = identityPrivate.calculateSignature(signedPair.publicKey.serialize())
        val signedRecord = SignedPreKeyRecord(signedId, System.currentTimeMillis(), signedPair, signedSig)
        store.storeSignedPreKey(signedId, signedRecord)

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

    /** The published bundle as a peer would fetch it, minus the one-time prekeys. */
    fun peerViewOf(bundle: KeyBundleDto, username: String, oneTimePrekeyCount: Int): PeerKeyBundleDto =
        PeerKeyBundleDto(
            username = username,
            registrationId = bundle.registrationId,
            identityKey = bundle.identityKey,
            signedPreKey = bundle.signedPreKey,
            kyberPreKey = bundle.kyberPreKey,
            oneTimePrekeyCount = oneTimePrekeyCount,
        )

    override fun processPeerBundle(peer: String, bundle: PeerKeyBundleDto, oneTimePreKey: OneTimePreKeyDto?): Result<Unit> = runCatching {
        val preKeyPublic = oneTimePreKey?.let { ECPublicKey(Base64.getDecoder().decode(it.publicKey)) }
        val preKeyId = oneTimePreKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID
        val identityKey = IdentityKey(Base64.getDecoder().decode(bundle.identityKey))
        val preKeyBundle = PreKeyBundle(
            registrationId = bundle.registrationId,
            deviceId = 1,
            preKeyId = preKeyId,
            preKeyPublic = preKeyPublic,
            signedPreKeyId = bundle.signedPreKey.id,
            signedPreKeyPublic = ECPublicKey(Base64.getDecoder().decode(bundle.signedPreKey.publicKey)),
            signedPreKeySignature = Base64.getDecoder().decode(bundle.signedPreKey.signature),
            identityKey = identityKey,
            kyberPreKeyId = bundle.kyberPreKey.id,
            kyberPreKeyPublic = KEMPublicKey(Base64.getDecoder().decode(bundle.kyberPreKey.publicKey)),
            kyberPreKeySignature = Base64.getDecoder().decode(bundle.kyberPreKey.signature),
        )
        SessionBuilder(store, address(peer)).process(preKeyBundle)
        store.saveIdentity(address(peer), identityKey)
        trustIdentity(peer, bundle.identityKey)
    }

    override fun encrypt(peer: String, plaintext: String): Result<String> = runCatching {
        check(hasSession(peer)) { "no session with $peer — process their key bundle first" }
        val message = SessionCipher(store, address(peer)).encrypt(plaintext.toByteArray(Charsets.UTF_8))
        Envelope.format(message.type, message.serialize())
    }

    override fun decrypt(peer: String, envelope: String): String? {
        val (type, bytes) = Envelope.parse(envelope) ?: return null
        if (!hasSession(peer) && type == CiphertextMessage.WHISPER_TYPE) return null
        val cipher = SessionCipher(store, address(peer))
        return try {
            when (type) {
                CiphertextMessage.PREKEY_TYPE -> String(cipher.decrypt(PreKeySignalMessage(bytes)), Charsets.UTF_8)
                CiphertextMessage.WHISPER_TYPE -> String(cipher.decrypt(SignalMessage(bytes)), Charsets.UTF_8)
                else -> null
            }
        } catch (e: UntrustedIdentityException) {
            // A changed identity key must never silently decrypt: flag it so
            // the UI can show the safety-number warning (ADR-0015 §3).
            changedIdentities.add(peer)
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun isIdentityTrusted(peer: String, identityKeyBase64: String): Boolean =
        trusted[peer] == null || trusted[peer] == identityKeyBase64

    override fun trustIdentity(peer: String, identityKeyBase64: String) {
        trusted[peer] = identityKeyBase64
    }

    override fun safetyNumber(peer: String): String? =
        trusted[peer]?.let { SafetyNumber.of(identityKeyPublicBase64, it) }

    override fun identityChanged(peer: String): Boolean = peer in changedIdentities

    override fun clearIdentityChange(peer: String) {
        changedIdentities.remove(peer)
    }
}

class LibsignalE2eeTest {

    private fun aliceAndBob(): Pair<LibsignalJvmEngine, LibsignalJvmEngine> {
        val alice = LibsignalJvmEngine()
        val bob = LibsignalJvmEngine()
        val bundle = alice.buildKeyBundle(oneTimePreKeyCount = 50)
        val oneTime = bundle.oneTimePreKeys.first()
        // Bob consumes exactly one of alice's one-time prekeys (the server's
        // atomic GET /v1/keys/{peer}/one-time) and establishes the session.
        bob.processPeerBundle("alice", alice.peerViewOf(bundle, "alice", oneTimePrekeyCount = 49), oneTime).getOrThrow()
        // Alice learns bob's bundle the same way to answer.
        val bobBundle = bob.buildKeyBundle(oneTimePreKeyCount = 50)
        alice.processPeerBundle("bob", bob.peerViewOf(bobBundle, "bob", 49), bobBundle.oneTimePreKeys.first()).getOrThrow()
        return alice to bob
    }

    @Test
    fun `PQXDH session - first message is a prekey envelope then ratchets both ways`() {
        val (alice, bob) = aliceAndBob()
        assertTrue(alice.hasSession("bob"))
        assertTrue(bob.hasSession("alice"))

        // Bob's first message must be a PREKEY_TYPE (3) envelope carrying the
        // PQXDH handshake; alice decrypts it and her session is established.
        val first = bob.encrypt("alice", "hello alice").getOrThrow()
        assertTrue(Envelope.isValid(first), "the envelope must satisfy the server wire contract")
        assertTrue(first.startsWith("3:"), "first message of a session is a prekey message, was: ${first.take(4)}")
        assertEquals("hello alice", alice.decrypt("bob", first))

        // From here on both directions are plain ratchet (WHISPER_TYPE = 2) messages.
        val reply = alice.encrypt("bob", "hi bob").getOrThrow()
        assertTrue(reply.startsWith("2:"), "after the handshake every message is a ratchet message")
        assertEquals("hi bob", bob.decrypt("alice", reply))

        repeat(5) { i ->
            val toBob = alice.encrypt("bob", "ping $i").getOrThrow()
            val toAlice = bob.encrypt("alice", "pong $i").getOrThrow()
            assertEquals("ping $i", bob.decrypt("alice", toBob))
            assertEquals("pong $i", alice.decrypt("bob", toAlice))
        }
    }

    @Test
    fun `unicode and long plaintexts round trip through the ratchet`() {
        val (alice, bob) = aliceAndBob()
        val secrets = listOf("héllo wörld 👋🔐", "ä" + "x".repeat(8000), "line1\nline2\ttab")
        for (secret in secrets) {
            val envelope = alice.encrypt("bob", secret).getOrThrow()
            assertTrue(Envelope.isValid(envelope), "envelope over the server size cap: ${envelope.length}")
            assertEquals(secret, bob.decrypt("alice", envelope))
        }
    }

    @Test
    fun `encrypt without a session fails instead of sending plaintext`() {
        val alice = LibsignalJvmEngine()
        val carol = LibsignalJvmEngine()
        assertFalse(carol.hasSession("alice"))
        val result = carol.encrypt("alice", "no session")
        assertTrue(result.isFailure)
        assertFailsWith<IllegalStateException> { result.getOrThrow() }
    }

    @Test
    fun `decrypt rejects malformed and tampered envelopes`() {
        val (alice, bob) = aliceAndBob()
        assertNull(alice.decrypt("bob", "not-an-envelope"))
        assertNull(alice.decrypt("bob", "1:AAAA"))           // wrong type digit
        assertNull(alice.decrypt("bob", "2:"))               // empty body
        assertNull(alice.decrypt("bob", "2:!!!not-base64!!!"))
        val envelope = bob.encrypt("alice", "integrity").getOrThrow()
        val (type, bytes) = Envelope.parse(envelope)!!
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        assertNull(alice.decrypt("bob", Envelope.format(type, bytes)), "a tampered ciphertext must fail the MAC")
    }

    @Test
    fun `envelope wire format matches the server contract exactly`() {
        val ciphertext = ByteArray(64) { (it * 7).toByte() }
        for (type in listOf(2, 3)) {
            val envelope = Envelope.format(type, ciphertext)
            assertTrue(Envelope.isValid(envelope))
            val parsed = Envelope.parse(envelope)
            assertNotNull(parsed)
            assertEquals(type, parsed.first)
            assertTrue(parsed.second.contentEquals(ciphertext))
        }
        assertFalse(Envelope.isValid("1:${Base64.getEncoder().encodeToString(ciphertext)}"))
        assertFalse(Envelope.isValid("2:"))
        assertFalse(Envelope.isValid("3:abc!def"))
        assertFalse(Envelope.isValid("2:${"A".repeat(Envelope.MAX_CHARS)}"), "over the server size cap")
        assertTrue(Envelope.isValid("2:${"A".repeat(100)}="))  // padding is legal
    }

    @Test
    fun `safety number is symmetric stable and identity-bound`() {
        val a = LibsignalJvmEngine()
        val b = LibsignalJvmEngine()
        val numberAB = SafetyNumber.of(a.identityKeyPublicBase64, b.identityKeyPublicBase64)
        val numberBA = SafetyNumber.of(b.identityKeyPublicBase64, a.identityKeyPublicBase64)
        assertEquals(numberAB, numberBA, "both devices must compute the same number")
        assertEquals(60, numberAB.replace(" ", "").length)
        assertEquals(12, numberAB.split(" ").size)
        assertTrue(numberAB.all { it.isDigit() || it == ' ' })
        val c = LibsignalJvmEngine()
        assertNotEquals(numberAB, SafetyNumber.of(a.identityKeyPublicBase64, c.identityKeyPublicBase64),
            "a different identity key must produce a different safety number")
        // The engine exposes the same number once the peer's identity is trusted.
        val bundle = a.buildKeyBundle(1)
        b.processPeerBundle("alice", a.peerViewOf(bundle, "alice", 0), bundle.oneTimePreKeys.first()).getOrThrow()
        assertEquals(numberAB, b.safetyNumber("alice"))
        assertNull(c.safetyNumber("alice"), "no identity known yet means no number")
    }

    @Test
    fun `identity trust tracks the published key and detects replacement`() {
        val (alice, bob) = aliceAndBob()
        val aliceKey = alice.identityKeyPublicBase64
        assertTrue(bob.isIdentityTrusted("alice", aliceKey))
        assertFalse(bob.isIdentityTrusted("alice", LibsignalJvmEngine().identityKeyPublicBase64),
            "a replaced identity key must not be trusted silently")
        bob.trustIdentity("alice", "bmV3LWtleQ==")
        assertTrue(bob.isIdentityTrusted("alice", "bmV3LWtleQ=="))
        assertFalse(bob.isIdentityTrusted("alice", aliceKey))
    }

    @Test
    fun `a session also works with the kyber prekey alone when no one-time prekey is left`() {
        val alice = LibsignalJvmEngine()
        val bob = LibsignalJvmEngine()
        val bundle = alice.buildKeyBundle(oneTimePreKeyCount = 0) // no one-time prekeys published
        assertTrue(bundle.oneTimePreKeys.isEmpty())
        bob.processPeerBundle("alice", alice.peerViewOf(bundle, "alice", 0), null).getOrThrow()
        val envelope = bob.encrypt("alice", "kyber only").getOrThrow()
        assertTrue(envelope.startsWith("3:"))
        assertEquals("kyber only", alice.decrypt("bob", envelope))
    }

    @Test
    fun `a replaced identity key blocks a new handshake until the new key is accepted`() {
        val alice = LibsignalJvmEngine()
        val bob = LibsignalJvmEngine()
        val b1 = alice.buildKeyBundle(1)
        bob.processPeerBundle("alice", alice.peerViewOf(b1, "alice", 0), b1.oneTimePreKeys.first()).getOrThrow()

        // alice "reinstalls": brand-new identity key, new published bundle.
        val alice2 = LibsignalJvmEngine()
        val b2 = alice2.buildKeyBundle(1)
        val refused = bob.processPeerBundle("alice", alice2.peerViewOf(b2, "alice", 0), b2.oneTimePreKeys.first())
        assertTrue(refused.isFailure, "libsignal must refuse the handshake for a changed identity")
        assertFailsWith<UntrustedIdentityException> { refused.getOrThrow() }

        // Once the new identity is accepted (the app engine's trustIdentity
        // writes the same store entry), the handshake processes and traffic
        // flows to the new device.
        val newKey = IdentityKey(Base64.getDecoder().decode(alice2.identityKeyPublicBase64))
        bob.store.saveIdentity(SignalProtocolAddress("alice", 1), newKey)
        bob.processPeerBundle("alice", alice2.peerViewOf(b2, "alice", 0), null).getOrThrow()
        val envelope = bob.encrypt("alice", "after reinstall").getOrThrow()
        assertEquals("after reinstall", alice2.decrypt("bob", envelope))
    }
}
