package com.patbaumgartner.stringtelephone

import java.io.File

/**
 * Asterisk configuration writer used by provisioning. Generates a PJSIP
 * endpoint/auth/aor fragment per account into the directory given by
 * ASTERISK_PJSIP_D (default /etc/asterisk/pjsip.d), keeping PBX and
 * companion identities in sync. The operator passes the SIP secret
 * explicitly; it is never persisted in the database.
 */
object AsteriskConfig {
    fun endpointFragment(username: String, secret: String): String = buildString {
        appendLine("[${username}](endpoint-basic)")
        appendLine("auth=${username}-auth")
        appendLine("aors=${username}")
        appendLine()
        appendLine("[${username}-auth]")
        appendLine("type=auth")
        appendLine("auth_type=userpass")
        appendLine("username=${username}")
        appendLine("password=$secret")
        appendLine()
        appendLine("[${username}](aor-basic)")
    }

    fun writeFragment(dir: File, username: String, secret: String) {
        require(dir.isDirectory || dir.mkdirs()) { "cannot create ${dir.path}" }
        File(dir, "$username.conf").writeText(endpointFragment(username, secret))
    }

    fun removeFragment(dir: File, username: String): Boolean {
        val f = File(dir, "$username.conf")
        return if (f.exists()) f.delete() else false
    }
}
