package com.patbaumgartner.android.stringtelephone.core

import java.util.UUID

/**
 * One-to-one conversation identifier derived from both usernames so both
 * clients compute the same id without a lookup. The server remains
 * authoritative; this mirrors its rule for optimistic local ordering.
 */
fun conversationIdBetween(userA: String, userB: String): String =
    listOf(userA, userB).sorted().joinToString("-", prefix = "dm-")

/** Client idempotency key for a send attempt. */
fun newMessageClientKey(): String = "k-${UUID.randomUUID()}"

/**
 * Cursor arithmetic for the paginated message API: opaque base64 of
 * "<epochMs>:<id>". The server validates it; the client only constructs it.
 */
fun encodeMessageCursor(epochMs: Long, messageId: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("$epochMs:$messageId".toByteArray())

fun decodeMessageCursor(cursor: String): Pair<Long, String>? = runCatching {
    val raw = String(java.util.Base64.getUrlDecoder().decode(cursor))
    val parts = raw.split(':', limit = 2)
    if (parts.size != 2) return@runCatching null
    parts[0].toLong() to parts[1]
}.getOrNull()
