package com.patbaumgartner.android.stringtelephone.core

import java.net.URI

/** PBX ports the app derives from the companion-API scheme. */
const val SIP_PORT_UNSECURED = 5060
const val SIP_PORT_SECURED = 5061

/**
 * WebSocket endpoint of the companion API derived from its base URL:
 * `https` maps to `wss` (TLS) and `http` to `ws` (cleartext), keeping the
 * same authority. Credentials must be sent in an Authorization header, never
 * in this URL, which can appear in proxy access logs.
 */
fun webSocketUrl(serverUrl: String): String {
    val validated = validateServerUrl(serverUrl)
    require(validated is ServerUrlValidation.Valid) { "Invalid server URL" }
    val scheme = if (validated.secure) "wss" else "ws"
    val authority = validated.normalized.substringAfter("://")
    return "$scheme://$authority/v1/ws"
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
    val validated = validateServerUrl(serverUrl) as? ServerUrlValidation.Valid ?: return null
    val host = URI(validated.normalized).host.removeSurrounding("[", "]")
    return if (validated.secure) {
        SipTarget(host = host, port = SIP_PORT_SECURED, secure = true)
    } else {
        SipTarget(host = host, port = SIP_PORT_UNSECURED, secure = false)
    }
}
