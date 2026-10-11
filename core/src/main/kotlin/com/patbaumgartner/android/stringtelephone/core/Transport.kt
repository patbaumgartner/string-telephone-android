package com.patbaumgartner.android.stringtelephone.core

import java.net.URI

/** PBX ports the app derives from the companion-API scheme. */
const val SIP_PORT_UNSECURED = 5060
const val SIP_PORT_SECURED = 5061

/**
 * WebSocket endpoint of the companion API derived from its base URL:
 * `https` maps to `wss` (TLS) and `http` to `ws` (cleartext), keeping the
 * same authority. The API token rides in the query string because browsers and
 * mobile WebSocket clients cannot set an Authorization header on the upgrade.
 */
fun webSocketUrl(serverUrl: String, token: String? = null): String {
    val normalized = serverUrl.trim().removeSuffix("/")
    val scheme = if (normalized.startsWith("https://")) "wss" else "ws"
    val authority = normalized.substringAfter("://")
    return buildString {
        append("$scheme://$authority/v1/ws")
        if (!token.isNullOrEmpty()) {
            append("?token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
        }
    }
}

/** SIP registrar the app registers against, derived from the server base URL. */
data class SipTarget(
    val host: String,
    val port: Int,
    val secure: Boolean,
)

/**
 * Derives the SIP registrar from the companion-API base URL: an https server
 * implies SIP over TLS on [SIP_PORT_SECURED], an http server plain SIP over
 * UDP on [SIP_PORT_UNSECURED]. The API port never determines the SIP port;
 * only the host is taken from the URL. Returns null when no host is present.
 */
fun deriveSipTarget(serverUrl: String): SipTarget? {
    val host = hostOf(serverUrl) ?: return null
    return if (serverUrl.trim().startsWith("https://")) {
        SipTarget(host = host, port = SIP_PORT_SECURED, secure = true)
    } else {
        SipTarget(host = host, port = SIP_PORT_UNSECURED, secure = false)
    }
}

private fun hostOf(serverUrl: String): String? {
    val trimmed = serverUrl.trim()
    val fromUri = runCatching { URI(trimmed).host }.getOrNull()
    if (!fromUri.isNullOrEmpty()) return fromUri.removeSurrounding("[", "]")
    // Fallback for inputs java.net.URI rejects (e.g. underscores in hostnames).
    val authority = trimmed.substringAfter("://", "").substringBefore('/').substringBefore('?')
    val host = authority.substringAfterLast('@').substringBeforeLast(':')
    return host.removeSurrounding("[", "]").ifEmpty { null }
}
