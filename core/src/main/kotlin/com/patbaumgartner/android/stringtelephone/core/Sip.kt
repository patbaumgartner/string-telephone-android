package com.patbaumgartner.android.stringtelephone.core

/**
 * Minimal SIP message parsing helpers, framework-free, for unit testing the
 * subset of SIP the app relies on (REGISTER/INVITE/BYE responses).
 */
data class SipResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
)

fun parseSipStatusLine(firstLine: String): Pair<Int, String>? {
    val match = Regex("SIP/2\\.0 (\\d+)\\s*(.*)").find(firstLine) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2]
}

fun parseSipResponse(raw: String): SipResponse? {
    val lines = raw.split("\r\n", "\n")
    val first = lines.firstOrNull() ?: return null
    val (status, reason) = parseSipStatusLine(first) ?: return null
    val headers = mutableMapOf<String, String>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }
    return SipResponse(status, reason, headers)
}

fun headerValue(response: SipResponse, name: String): String? =
    response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/** Extracts the digest challenge parameters from a 401 response. */
fun digestChallengeFrom(response: SipResponse): Map<String, String>? {
    val www = headerValue(response, "WWW-Authenticate") ?: return null
    if (www.isEmpty()) return null
    return parseDigestChallenge(www)
}
