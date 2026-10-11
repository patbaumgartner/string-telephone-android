package com.patbaumgartner.stringtelephone

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class PbxSettings(
    val codecs: String = "opus,ulaw",
    val ringSeconds: Int = 30,
    val maxContacts: Int = 2,
    val externalAddress: String = "",
    val localNetwork: String = "",
    val symmetricMedia: Boolean = true,
) {
    fun validate() {
        require(codecs.split(",").isNotEmpty() && codecs.split(",").all { it in setOf("opus", "ulaw", "alaw") }) {
            "Codecs must be a comma-separated selection of opus, ulaw, alaw"
        }
        require(ringSeconds in 5..120) { "Ring duration must be between 5 and 120 seconds" }
        require(maxContacts in 1..5) { "Devices per account must be between 1 and 5" }
        require(externalAddress.isEmpty() || validIpv4(externalAddress)) { "External address must be an IPv4 address" }
        require(localNetwork.isEmpty() || localNetwork.split("/").let {
            it.size == 2 && validIpv4(it[0]) && it[1].toIntOrNull() in 0..32
        }) { "Local network must be IPv4/CIDR, for example 192.168.1.0/24" }
        require(externalAddress.isEmpty() == localNetwork.isEmpty()) { "Set both external address and local network, or leave both empty" }
    }
}

private fun validIpv4(value: String) =
    value.split(".").let { parts -> parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 } }

data class PbxAccount(val username: String, val digest: String)

fun validateUsername(username: String) {
    require(username.matches(Regex("[a-z][a-z0-9-]{0,31}")) && username !in setOf("a", "e", "h", "i", "o", "s", "t")) {
        "Username must start with a lowercase letter, use only lowercase letters, numbers or hyphens, and be at most 32 characters; a, e, h, i, o, s and t are reserved"
    }
}

fun sipDigest(username: String, secret: String): String {
    validateUsername(username)
    require(secret.length in 24..1024 && secret.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "SIP password must be 24 to 1024 characters with no line breaks or NUL characters"
    }
    return MessageDigest.getInstance("MD5").digest("$username:asterisk:$secret".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object AsteriskConfig {
    fun endpoints(accounts: List<PbxAccount>, settings: PbxSettings, requireTls: Boolean = false): String = buildString {
        settings.validate()
        appendLine("; Managed by String Telephone. Do not edit.")
        if (!requireTls) {
            appendLine("[transport-udp]")
            appendLine("type=transport")
            appendLine("protocol=udp")
            appendLine("bind=0.0.0.0:5060")
            appendLine("allow_reload=yes")
            if (settings.externalAddress.isNotEmpty()) {
                appendLine("external_media_address=${settings.externalAddress}")
                appendLine("external_signaling_address=${settings.externalAddress}")
                appendLine("local_net=${settings.localNetwork}")
            }
        }
        accounts.sortedBy { it.username }.forEach { account ->
            validateUsername(account.username)
            require(account.digest.matches(Regex("[0-9a-f]{32}"))) { "Invalid SIP digest" }
            appendLine()
            appendLine("[${account.username}]")
            appendLine("type=endpoint")
            appendLine("context=from-internal")
            appendLine("auth=${account.username}-auth")
            appendLine("aors=${account.username}")
            appendLine("disallow=all")
            appendLine("allow=${settings.codecs}")
            appendLine("direct_media=no")
            if (settings.externalAddress.isNotEmpty()) appendLine("media_address=${settings.externalAddress}")
            appendLine("dtmf_mode=rfc4733")
            appendLine("rtp_symmetric=${if (settings.symmetricMedia) "yes" else "no"}")
            appendLine("force_rport=yes")
            appendLine("rewrite_contact=yes")
            if (requireTls) appendLine("transport=transport-tls")
            appendLine("media_encryption_optimistic=${if (requireTls) "no" else "yes"}")
            appendLine("media_encryption=sdes")
            appendLine("[${account.username}-auth]")
            appendLine("type=auth")
            appendLine("auth_type=digest")
            appendLine("realm=asterisk")
            appendLine("username=${account.username}")
            appendLine("password_digest=MD5:${account.digest}")
            appendLine("[${account.username}]")
            appendLine("type=aor")
            appendLine("max_contacts=${settings.maxContacts}")
            appendLine("remove_existing=yes")
            appendLine("qualify_frequency=30")
        }
    }

    fun dialplan(accounts: List<PbxAccount>, settings: PbxSettings): String = buildString {
        settings.validate()
        appendLine("[from-internal]")
        accounts.sortedBy { it.username }.forEach {
            validateUsername(it.username)
            appendLine("exten => ${it.username},1,Dial(PJSIP/${it.username},${settings.ringSeconds})")
            appendLine("same => n,Hangup()")
        }
    }
}
