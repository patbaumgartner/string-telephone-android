package com.patbaumgartner.android.stringtelephone.core

import java.security.MessageDigest

/** Result of validating a user-entered server base URL. */
sealed interface ServerUrlValidation {
    data class Valid(val normalized: String) : ServerUrlValidation
    data class Invalid(val reason: String) : ServerUrlValidation
}

/**
 * Validates a user-supplied self-hosted server base URL. Production rules:
 * https scheme required, host required, no credentials in URL.
 */
fun validateServerUrl(raw: String): ServerUrlValidation {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ServerUrlValidation.Invalid("URL is empty")
    if (!trimmed.contains("://")) return ServerUrlValidation.Invalid("URL must start with https://")
    val scheme = trimmed.substringBefore("://").lowercase()
    if (scheme != "https") return ServerUrlValidation.Invalid("Only https:// is allowed")
    val rest = trimmed.removePrefix("$scheme://")
    val authority = rest.substringBefore('/')
    if (authority.isEmpty()) return ServerUrlValidation.Invalid("Missing host")
    if (authority.contains('@')) return ServerUrlValidation.Invalid("Credentials in URL are not supported")
    val path = rest.substringAfter('/', "")
    if (path.contains("//")) return ServerUrlValidation.Invalid("Invalid path")
    return ServerUrlValidation.Valid("$scheme://$authority")
}

private fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it) }

/**
 * SIP digest (MD5, qop=auth) response computation. Pure function so it is
 * unit-testable without any Android or network dependency.
 */
fun digestResponse(
    username: String,
    password: String,
    realm: String,
    nonce: String,
    method: String,
    uri: String,
    nc: String = "00000001",
    cnonce: String,
): String {
    val a1 = md5Hex("$username:$realm:$password")
    val a2 = md5Hex("$method:$uri")
    return md5Hex("$a1:$nonce:$nc:$cnonce:auth:$a2")
}

/** Builds the Authorization header value for a digest challenge. */
fun digestAuthorizationHeader(
    username: String,
    password: String,
    realm: String,
    nonce: String,
    opaque: String?,
    method: String,
    uri: String,
    cnonce: String,
    nc: String = "00000001",
): String {
    val response = digestResponse(username, password, realm, nonce, method, uri, nc, cnonce)
    return buildString {
        append("Digest username=\"$username\",realm=\"$realm\",nonce=\"$nonce\",")
        append("uri=\"$uri\",response=\"$response\",algorithm=MD5,qop=auth,nc=$nc,")
        append("cnonce=\"$cnonce\"")
        if (!opaque.isNullOrEmpty()) append(",opaque=\"$opaque\"")
    }
}

/** Parses a WWW-Authenticate header value into its challenge parameters. */
fun parseDigestChallenge(headerValue: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val body = headerValue.substringAfter("Digest", headerValue).trim()
    val re = Regex("(\\w+)=\"([^\"]*)\"|(\\w+)=([^,\\s\"]+)")
    for (match in re.findAll(body)) {
        val key = match.groupValues[1].ifEmpty { match.groupValues[3] }
        val value = if (match.groupValues[2].isNotEmpty()) match.groupValues[2] else match.groupValues[4]
        out[key] = value
    }
    return out
}
