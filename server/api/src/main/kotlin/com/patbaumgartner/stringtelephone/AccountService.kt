package com.patbaumgartner.stringtelephone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.support.TransactionTemplate

data class ManagedAccount(val username: String, val displayName: String, val enabled: Boolean, val active: Boolean)
data class AccountCredentials(val username: String, val displayName: String, val apiToken: String, val sipSecret: String, val ready: Boolean)
data class SyncStatus(val ready: Boolean, val error: String?)

class AccountService(
    private val db: JdbcClient,
    private val tx: TransactionTemplate,
    private val users: Users,
    private val pbx: PbxGateway,
    private val hub: WsHub,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(AccountService::class.java)
    private val reconcileLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var appliedFingerprint: String? = null
    @Volatile var syncStatus = SyncStatus(false, "PBX has not yet been reconciled")
        private set

    fun list(): List<ManagedAccount> =
        db.sql(
            "SELECT u.username,u.display_name,u.active,p.enabled FROM users u " +
                "LEFT JOIN pbx_accounts p ON u.username=p.username ORDER BY u.username",
        ).query { rs, _ ->
            ManagedAccount(rs.getString("username"), rs.getString("display_name"), rs.getBoolean("enabled"), rs.getBoolean("active"))
        }.list()

    // Multi-statement writes run in one transaction so a half-applied account
    // never survives; the PBX reconciliation below stays outside it — no
    // network I/O while a database transaction is open.
    fun create(username: String, displayName: String, password: String?): AccountCredentials {
        validateUsername(username)
        require(displayName.isNotBlank() && displayName.length <= 100) { "Display name must be 1 to 100 characters" }
        if (users.find(username) != null) throw ApiFailure(org.springframework.http.HttpStatus.CONFLICT, "Username already exists")
        val secret = password?.takeIf { it.isNotEmpty() } ?: Secrets.generate(32)
        val digest = sipDigest(username, secret)
        val token = "st_" + Secrets.generate(32)
        tx.executeWithoutResult {
            db.sql("INSERT INTO users(id,username,display_name,active,api_token_hash,created_at) VALUES(?,?,?,0,?,?)")
                .params(newId(), username, displayName.trim(), sha256Hex(token), System.currentTimeMillis())
                .update()
            db.sql("INSERT INTO pbx_accounts(username,digest,enabled) VALUES(?,?,1)")
                .params(username, digest)
                .update()
        }
        reconcile()
        return AccountCredentials(username, displayName.trim(), token, secret, syncStatus.ready)
    }

    fun reset(username: String, password: String?): AccountCredentials {
        val user = users.find(username) ?: throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No such user")
        val secret = password?.takeIf { it.isNotEmpty() } ?: Secrets.generate(32)
        val digest = sipDigest(username, secret)
        val token = "st_" + Secrets.generate(32)
        tx.executeWithoutResult {
            db.sql("UPDATE users SET active=0,api_token_hash=? WHERE username=?")
                .params(sha256Hex(token), username)
                .update()
            val updated = db.sql("UPDATE pbx_accounts SET digest=?,kill_required=1 WHERE username=?")
                .params(digest, username)
                .update()
            require(updated == 1) { "Legacy account has no managed PBX identity; use a fresh installation" }
        }
        hub.disconnect(username)
        reconcile()
        return AccountCredentials(username, user.displayName, token, secret, syncStatus.ready)
    }

    fun enabled(username: String, enabled: Boolean) {
        validateUsername(username)
        tx.executeWithoutResult {
            val updated = db.sql("UPDATE pbx_accounts SET enabled=?,kill_required=1 WHERE username=?")
                .param(if (enabled) 1 else 0)
                .param(username)
                .update()
            if (updated == 0) throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No managed account")
            // `active` follows `enabled` in the same statement: relying on the
            // reconcile below to restore it left the account deactivated
            // whenever the PBX was unreachable.
            db.sql("UPDATE users SET active=?,revoked_at=? WHERE username=?")
                .param(if (enabled) 1 else 0)
                .param(if (enabled) null else System.currentTimeMillis())
                .param(username)
                .update()
        }
        hub.disconnect(username)
        reconcile()
        if (!syncStatus.ready) throw PbxUnavailable(syncStatus.error.orEmpty())
    }

    fun rename(username: String, displayName: String) {
        require(displayName.isNotBlank() && displayName.length <= 100) { "Display name must be 1 to 100 characters" }
        val updated = db.sql("UPDATE users SET display_name=? WHERE username=?")
            .params(displayName.trim(), username)
            .update()
        if (updated == 0) throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "No such user")
    }

    fun settings(): PbxSettings {
        val stored = db.sql("SELECT settings FROM pbx_settings WHERE id=1")
            .query(String::class.java)
            .optional()
            .orElse(null)
        return stored?.let { Json.decodeFromString(it) } ?: PbxSettings()
    }

    fun settings(settings: PbxSettings) {
        settings.validate()
        db.sql("INSERT INTO pbx_settings(id,settings) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET settings=excluded.settings")
            .param(Json.encodeToString(settings))
            .update()
        reconcile()
        if (!syncStatus.ready) throw PbxUnavailable(syncStatus.error.orEmpty())
    }

    @Scheduled(initialDelay = 1500, fixedDelay = 15000)
    fun reconcile() = withReconcileLock { reconcileLocked() }

    /**
     * Reconciles desired state to the PBX while holding only the reconcile
     * lock, so one slow AMI call serialises against another reconcile without
     * blocking unrelated admin requests.
     */
    fun <T> withReconcileLock(block: () -> T): T {
        reconcileLock.lock()
        return try {
            block()
        } finally {
            reconcileLock.unlock()
        }
    }

    private fun reconcileLocked() {
        try {
            val rows = db.sql("SELECT username,digest,enabled,kill_required FROM pbx_accounts ORDER BY username")
                .query { rs, _ -> DesiredAccount(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)) }
                .list()
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
            tx.executeWithoutResult {
                db.sql("UPDATE users SET active=COALESCE((SELECT enabled FROM pbx_accounts WHERE pbx_accounts.username=users.username),0)").update()
                db.sql("UPDATE pbx_accounts SET kill_required=0").update()
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
