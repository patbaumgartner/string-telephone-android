package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * The E2EE key directory (ADR-0015, Phase 22): the public key material
 * clients publish so peers can run X3DH/PQXDH against them. The server
 * stores and relays these blobs verbatim — it never sees the private halves
 * and treats every value as opaque base64.
 *
 * Publishing is idempotent and additive: identity, signed prekey and Kyber
 * last-resort prekey are upserted (a re-publish replaces them), while the
 * one-time prekeys in a bundle are inserted with ON CONFLICT DO NOTHING so
 * a top-up never destroys prekeys another device already handed out.
 * Consuming a one-time prekey is a single `DELETE ... RETURNING`, so two
 * racing senders can never both receive the same prekey.
 */
class Keys(
    private val db: JdbcClient,
    private val tx: TransactionTemplate,
) {

    /** Publishes (or tops up) the caller's key bundle. Multi-statement, hence the transaction. */
    fun publish(user: String, bundle: KeyBundleDto) {
        require(bundle.identityKey.isNotBlank()) { "identityKey must not be blank" }
        require(bundle.signedPreKey.publicKey.isNotBlank() && bundle.signedPreKey.signature.isNotBlank()) {
            "signed prekey incomplete"
        }
        require(bundle.kyberPreKey.publicKey.isNotBlank() && bundle.kyberPreKey.signature.isNotBlank()) {
            "kyber prekey incomplete"
        }
        tx.execute {
            val now = System.currentTimeMillis()
            db.sql(
                "INSERT INTO identity_keys(username, registration_id, identity_key, updated_at) VALUES(?, ?, ?, ?) " +
                    "ON CONFLICT (username) DO UPDATE SET registration_id = EXCLUDED.registration_id, " +
                    "identity_key = EXCLUDED.identity_key, updated_at = EXCLUDED.updated_at",
            ).params(user, bundle.registrationId, bundle.identityKey, now).update()
            db.sql(
                "INSERT INTO signed_prekeys(username, prekey_id, public_key, signature, updated_at) VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT (username, prekey_id) DO UPDATE SET public_key = EXCLUDED.public_key, " +
                    "signature = EXCLUDED.signature, updated_at = EXCLUDED.updated_at",
            ).params(user, bundle.signedPreKey.id, bundle.signedPreKey.publicKey, bundle.signedPreKey.signature, now).update()
            db.sql(
                "INSERT INTO kyber_prekeys(username, prekey_id, public_key, signature, updated_at) VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT (username, prekey_id) DO UPDATE SET public_key = EXCLUDED.public_key, " +
                    "signature = EXCLUDED.signature, updated_at = EXCLUDED.updated_at",
            ).params(user, bundle.kyberPreKey.id, bundle.kyberPreKey.publicKey, bundle.kyberPreKey.signature, now).update()
            for (preKey in bundle.oneTimePreKeys) {
                db.sql(
                    "INSERT INTO one_time_prekeys(username, prekey_id, public_key, created_at) VALUES(?, ?, ?, ?) " +
                        "ON CONFLICT (username, prekey_id) DO NOTHING",
                ).params(user, preKey.id, preKey.publicKey, now).update()
            }
        }
    }

    /**
     * Another user's published bundle, as a sender needs it to establish a
     * session: identity key + registration id + signed prekey + Kyber
     * last-resort prekey + how many one-time prekeys remain. Null when the
     * peer never published keys (pre-E2EE peer).
     */
    fun peerBundle(user: String): PeerKeyBundleDto? {
        val identity = db.sql(
            "SELECT registration_id, identity_key FROM identity_keys WHERE username = ?",
        ).param(user).query { rs, _ -> rs.getInt("registration_id") to rs.getString("identity_key") }
            .optional().orElse(null) ?: return null
        val signed = db.sql(
            "SELECT prekey_id, public_key, signature FROM signed_prekeys WHERE username = ? ORDER BY updated_at DESC LIMIT 1",
        ).param(user).query { rs, _ ->
            SignedPreKeyDto(rs.getInt("prekey_id"), rs.getString("public_key"), rs.getString("signature"))
        }.optional().orElse(null) ?: return null
        val kyber = db.sql(
            "SELECT prekey_id, public_key, signature FROM kyber_prekeys WHERE username = ? ORDER BY updated_at DESC LIMIT 1",
        ).param(user).query { rs, _ ->
            KyberPreKeyDto(rs.getInt("prekey_id"), rs.getString("public_key"), rs.getString("signature"))
        }.optional().orElse(null) ?: return null
        return PeerKeyBundleDto(
            user, identity.first, identity.second, signed, kyber, oneTimeCount(user),
        )
    }

    /** The caller's own bundle plus the remaining one-time prekey count (top-up signal). */
    fun ownBundle(user: String): OwnKeyBundleDto? {
        val peer = peerBundle(user) ?: return null
        return OwnKeyBundleDto(peer.registrationId, peer.identityKey, peer.signedPreKey, peer.kyberPreKey, peer.oneTimePrekeyCount)
    }

    /** Remaining one-time prekeys of [user]. */
    fun oneTimeCount(user: String): Int =
        db.sql("SELECT COUNT(*) FROM one_time_prekeys WHERE username = ?")
            .param(user).query(Int::class.java).single()

    /**
     * Atomically hands out one of [user]'s one-time prekeys: the DELETE is
     * the consumption, so a prekey is returned to exactly one caller.
     * Null when the user has none left (senders then fall back to the
     * signed + Kyber prekeys only).
     */
    fun consumeOneTimePreKey(user: String): OneTimePreKeyDto? =
        db.sql(
            "DELETE FROM one_time_prekeys WHERE username = ? AND prekey_id = " +
                "(SELECT prekey_id FROM one_time_prekeys WHERE username = ? ORDER BY created_at, prekey_id LIMIT 1) " +
                "RETURNING prekey_id, public_key",
        ).params(user, user).query { rs, _ ->
            OneTimePreKeyDto(rs.getInt("prekey_id"), rs.getString("public_key"))
        }.optional().orElse(null)
}
