package com.patbaumgartner.stringtelephone

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

class Users(private val db: Database) {

    fun create(username: String, displayName: String): Pair<UserRecord, String> {
        val token = "st_" + Secrets.generate()
        val tokenHash = sha256Hex(token)
        val id = newId()
        val now = System.currentTimeMillis()
        db.tx { conn ->
            conn.prepareStatement(
                "INSERT INTO users(id, username, display_name, active, api_token_hash, created_at) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, username)
                ps.setString(3, displayName)
                ps.setInt(4, 1)
                ps.setString(5, tokenHash)
                ps.setLong(6, now)
                ps.executeUpdate()
            }
        }
        return UserRecord(id, username, displayName, true, tokenHash, now, null) to token
    }

    fun revoke(username: String): Boolean = db.tx { conn ->
        conn.prepareStatement("UPDATE users SET active=0, revoked_at=? WHERE username=?").use { ps ->
            ps.setLong(1, System.currentTimeMillis())
            ps.setString(2, username)
            ps.executeUpdate() > 0
        }
    }

    fun list(): List<UserRecord> = db.read { conn ->
        conn.prepareStatement(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at FROM users ORDER BY username",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                val out = mutableListOf<UserRecord>()
                while (rs.next()) {
                    out += UserRecord(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getInt("active") == 1,
                        rs.getString("api_token_hash"),
                        rs.getLong("created_at"),
                        rs.getObject("revoked_at") as Long?,
                    )
                }
                out
            }
        }
    }

    fun find(username: String): UserRecord? = db.read { conn ->
        conn.prepareStatement(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at FROM users WHERE username = ?",
        ).use { ps ->
            ps.setString(1, username)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@read null
                UserRecord(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getInt("active") == 1,
                    rs.getString("api_token_hash"),
                    rs.getLong("created_at"),
                    rs.getObject("revoked_at") as Long?,
                )
            }
        }
    }

    fun findByToken(token: String): UserRecord? = db.read { conn ->
        val hash = sha256Hex(token)
        conn.prepareStatement(
            "SELECT id, username, display_name, active, api_token_hash, created_at, revoked_at FROM users WHERE api_token_hash = ? AND active = 1",
        ).use { ps ->
            ps.setString(1, hash)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@read null
                UserRecord(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    rs.getInt("active") == 1,
                    rs.getString("api_token_hash"),
                    rs.getLong("created_at"),
                    rs.getObject("revoked_at") as Long?,
                )
            }
        }
    }
}
