package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.security.SecureRandom

data class UserRecord(
    val id: String,
    val username: String,
    val displayName: String,
    val active: Boolean,
    val apiTokenHash: String,
    val createdAt: Long,
    val revokedAt: Long?,
    /** When the user's last realtime session disconnected; null when never connected or currently online. */
    val lastSeenAt: Long? = null,
)

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val active: Boolean,
    val createdAt: Long,
    val revokedAt: Long?,
)

/** Generates cryptographically random secrets (SIP and API credentials). */
object Secrets {
    private val random = SecureRandom()

    fun generate(lengthBytes: Int = 24): String {
        val bytes = ByteArray(lengthBytes)
        random.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun newId(): String = java.util.UUID.randomUUID().toString()

class Users(private val db: JdbcClient) {
    private fun record(rs: java.sql.ResultSet) = UserRecord(
        rs.getString("id"),
        rs.getString("username"),
        rs.getString("display_name"),
        rs.getInt("active") == 1,
        rs.getString("api_token_hash"),
        rs.getLong("created_at"),
        rs.getLong("revoked_at").takeUnless { rs.wasNull() },
        rs.getLong("last_seen_at").takeUnless { rs.wasNull() },
    )

    // Single-statement writes are atomic on their own; every value is bound
    // through param()/params(), never concatenated into the SQL text.
    fun create(username: String, displayName: String): Pair<UserRecord, String> {
        val token = "st_" + Secrets.generate()
        val tokenHash = sha256Hex(token)
        val id = newId()
        val now = System.currentTimeMillis()
        db.sql(
            "INSERT INTO users(id, username, display_name, active, api_token_hash, created_at) VALUES(?,?,?,?,?,?)",
        ).params(id, username, displayName, 1, tokenHash, now).update()
        return UserRecord(id, username, displayName, true, tokenHash, now, null) to token
    }

    fun revoke(username: String): Boolean =
        db.sql("UPDATE users SET active=0, revoked_at=? WHERE username=?")
            .params(System.currentTimeMillis(), username)
            .update() > 0

    fun list(): List<UserRecord> =
        db.sql(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at, last_seen_at FROM users ORDER BY username",
        ).query { rs, _ -> record(rs) }.list()

    fun find(username: String): UserRecord? =
        db.sql(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at, last_seen_at FROM users WHERE username = ?",
        ).param(username).query { rs, _ -> record(rs) }.optional().orElse(null)

    fun findByToken(token: String): UserRecord? {
        val hash = sha256Hex(token)
        return db.sql(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at, last_seen_at " +
                "FROM users WHERE api_token_hash = ? AND active = 1",
        ).param(hash).query { rs, _ -> record(rs) }.optional().orElse(null)
    }

    /**
     * Stamps [username]'s last-seen (Phase 13). Called when their last
     * realtime session disconnects or the account is disabled — "was last
     * reachable", never "last clicked". Truncated to the minute so the
     * stored value carries no more precision than the UI shows. A user who
     * is currently connected has no last-seen; online is the live state.
     */
    fun markLastSeen(username: String, at: Long): Boolean =
        db.sql("UPDATE users SET last_seen_at = ? WHERE username = ?")
            .params(truncateToMinute(at), username)
            .update() > 0

    fun clearLastSeen(username: String): Boolean =
        db.sql("UPDATE users SET last_seen_at = NULL WHERE username = ?")
            .param(username)
            .update() > 0

    companion object {
        /** Last-seen is minute-granular: never expose the exact second. */
        fun truncateToMinute(epochMs: Long): Long = epochMs / 60_000 * 60_000
    }
}
