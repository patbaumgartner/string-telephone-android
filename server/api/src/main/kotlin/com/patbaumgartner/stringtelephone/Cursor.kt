package com.patbaumgartner.stringtelephone

/** Cursor codec mirroring the client core module's contract. */
fun encodeMessageCursor(epochMs: Long, messageId: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$epochMs:$messageId".toByteArray())

fun decodeMessageCursor(cursor: String): Pair<Long, String>? = runCatching {
    val raw = String(java.util.Base64.getUrlDecoder().decode(cursor))
    val parts = raw.split(':', limit = 2)
    if (parts.size != 2) return@runCatching null
    parts[0].toLong() to parts[1]
}.getOrNull()
