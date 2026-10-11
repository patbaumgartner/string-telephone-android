package com.patbaumgartner.stringtelephone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.scheduling.annotation.Scheduled

data class ManagedAccount(val username: String, val displayName: String, val enabled: Boolean, val active: Boolean)
data class AccountCredentials(val username: String, val displayName: String, val apiToken: String, val sipSecret: String, val ready: Boolean)
data class SyncStatus(val ready: Boolean, val error: String?)

class AccountService(private val db: Database, private val users: Users, private val pbx: PbxGateway, private val hub: WsHub) {
    private val log = org.slf4j.LoggerFactory.getLogger(AccountService::class.java)
    private var appliedFingerprint: String? = null
    @Volatile var syncStatus = SyncStatus(false, "PBX has not yet been reconciled")
        private set

    fun list(): List<ManagedAccount> = db.read { conn ->
        conn.prepareStatement("SELECT u.username,u.display_name,u.active,p.enabled FROM users u LEFT JOIN pbx_accounts p ON u.username=p.username ORDER BY u.username")
            .use { statement -> statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ManagedAccount(rs.getString("username"), rs.getString("display_name"), rs.getBoolean("enabled"), rs.getBoolean("active"))) }
            } }
    }

    @Synchronized
    fun create(username: String, displayName: String, password: String?): AccountCredentials {
        validateUsername(username)
        require(displayName.isNotBlank() && displayName.length <= 100) { "Display name must be 1 to 100 characters" }
        if (users.find(username) != null) throw ApiFailure(org.springframework.http.HttpStatus.CONFLICT, "Username already exists")
        val secret = password?.takeIf { it.isNotEmpty() } ?: Secrets.generate(32)
        val digest = sipDigest(username, secret)
        val token = "st_" + Secrets.generate(32)
        db.tx { conn ->
            conn.prepareStatement("INSERT INTO users(id,username,display_name,active,api_token_hash,created_at) VALUES(?,?,?,0,?,?)").use {
                it.setString(1, newId()); it.setString(2, username); it.setString(3, displayName.trim())
                it.setString(4, sha256Hex(token)); it.setLong(5, System.currentTimeMillis()); it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO pbx_accounts(username,digest,enabled) VALUES(?,?,1)").use {
                it.setString(1, username); it.setString(2, digest); it.executeUpdate()
            }
        }
        reconcile()
        return AccountCredentials(username, displayName.trim(), token, secret, syncStatus.ready)
    }

    @Synchronized
    fun reset(username: String, password: String?): AccountCredentials {
        val user = users.find(username) ?: throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No such user")
        val secret = password?.takeIf { it.isNotEmpty() } ?: Secrets.generate(32)
        val digest = sipDigest(username, secret)
        val token = "st_" + Secrets.generate(32)
        db.tx { conn ->
            conn.prepareStatement("UPDATE users SET active=0,api_token_hash=? WHERE username=?").use {
                it.setString(1, sha256Hex(token)); it.setString(2, username); it.executeUpdate()
            }
            conn.prepareStatement("UPDATE pbx_accounts SET digest=?,kill_required=1 WHERE username=?").use {
                it.setString(1, digest); it.setString(2, username)
                require(it.executeUpdate() == 1) { "Legacy account has no managed PBX identity; use a fresh installation" }
            }
        }
        hub.disconnect(username)
        reconcile()
        return AccountCredentials(username, user.displayName, token, secret, syncStatus.ready)
    }

    @Synchronized
    fun enabled(username: String, enabled: Boolean) {
        validateUsername(username)
        db.tx { conn ->
            conn.prepareStatement("UPDATE pbx_accounts SET enabled=?,kill_required=1 WHERE username=?").use {
                it.setBoolean(1, enabled); it.setString(2, username)
                if (it.executeUpdate() == 0) throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No managed account")
            }
            conn.prepareStatement("UPDATE users SET active=0,revoked_at=? WHERE username=?").use {
                it.setObject(1, if (enabled) null else System.currentTimeMillis()); it.setString(2, username); it.executeUpdate()
            }
        }
        hub.disconnect(username)
        reconcile()
        if (!syncStatus.ready) throw PbxUnavailable(syncStatus.error.orEmpty())
    }

    @Synchronized
    fun rename(username: String, displayName: String) {
        require(displayName.isNotBlank() && displayName.length <= 100) { "Display name must be 1 to 100 characters" }
        db.tx { conn ->
            conn.prepareStatement("UPDATE users SET display_name=? WHERE username=?").use {
                it.setString(1, displayName.trim()); it.setString(2, username)
                if (it.executeUpdate() == 0) throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No such user")
            }
        }
    }

    fun settings(): PbxSettings = db.read { conn ->
        conn.prepareStatement("SELECT settings FROM pbx_settings WHERE id=1").use { statement ->
            statement.executeQuery().use { if (it.next()) Json.decodeFromString(it.getString(1)) else PbxSettings() }
        }
    }

    @Synchronized
    fun settings(settings: PbxSettings) {
        settings.validate()
        db.tx { conn ->
            conn.prepareStatement("INSERT INTO pbx_settings(id,settings) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET settings=excluded.settings").use {
                it.setString(1, Json.encodeToString(settings)); it.executeUpdate()
            }
        }
        reconcile()
        if (!syncStatus.ready) throw PbxUnavailable(syncStatus.error.orEmpty())
    }

    @Synchronized
    @Scheduled(initialDelay = 1500, fixedDelay = 15000)
    fun reconcile() {
        try {
            val rows = db.read { conn ->
                conn.prepareStatement("SELECT username,digest,enabled,kill_required FROM pbx_accounts ORDER BY username").use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(DesiredAccount(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4))) }
                    }
                }
            }
            val settings = settings()
            val fingerprint = sha256Hex(rows.toString() + Json.encodeToString(settings))
            if (syncStatus.ready && appliedFingerprint == fingerprint) {
                if (pbx.isCurrent(rows.filter { it.enabled }.map { PbxAccount(it.username, it.digest) }, settings)) return
            }
            val pending = rows.filter { it.kill }
            if (pending.isNotEmpty()) {
                // Remove pending identities before hanging up so old credentials cannot race a new call.
                pbx.apply(rows.filter { it.enabled && !it.kill }.map { PbxAccount(it.username, it.digest) }, settings)
                pending.forEach { pbx.disconnect(it.username) }
            }
            pbx.apply(rows.filter { it.enabled }.map { PbxAccount(it.username, it.digest) }, settings)
            db.tx { conn ->
                conn.createStatement().use {
                    it.executeUpdate("UPDATE users SET active=COALESCE((SELECT enabled FROM pbx_accounts WHERE pbx_accounts.username=users.username),0)")
                    it.executeUpdate("UPDATE pbx_accounts SET kill_required=0")
                }
            }
            syncStatus = SyncStatus(true, null)
            appliedFingerprint = sha256Hex(rows.map { it.copy(kill = false) }.toString() + Json.encodeToString(settings))
        } catch (error: PbxUnavailable) {
            syncStatus = SyncStatus(false, error.message)
            log.error("PBX reconciliation failed: {}", error.message)
        }
    }

    private data class DesiredAccount(val username: String, val digest: String, val enabled: Boolean, val kill: Boolean)
}
