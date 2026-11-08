package com.patbaumgartner.android.stringtelephone.core.e2ee

import java.security.MessageDigest

/**
 * Safety number (ADR-0015, Phase 22): a human-comparable fingerprint of the
 * two identity keys in a conversation. Both devices compute the same number
 * from the same key pair regardless of who is "local", so users can verify
 * out-of-band that nobody replaced a key (the classic MITM check).
 *
 * JDK-only: SHA-256 over the two base64 identity keys in sorted order; the
 * first 30 digest bytes become 60 decimal digits, rendered in 12 groups of 5.
 */
object SafetyNumber {
    const val DIGITS = 60
    const val GROUP_SIZE = 5

    /** 60 digits grouped by 5, e.g. `"12345 67890 ..."`, symmetric in its arguments. */
    fun of(localIdentityKeyBase64: String, remoteIdentityKeyBase64: String): String {
        val (a, b) = listOf(localIdentityKeyBase64, remoteIdentityKeyBase64).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((a + b).toByteArray(Charsets.UTF_8))
        val digits = StringBuilder(DIGITS)
        for (i in 0 until DIGITS / 2) {
            val byte = digest[i].toInt() and 0xFF
            digits.append("%02d".format(byte % 100)) // exactly two decimal digits per byte
        }
        return digits.toString().chunked(GROUP_SIZE).joinToString(" ")
    }
}
