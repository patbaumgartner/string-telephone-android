package com.patbaumgartner.android.stringtelephone.core

/**
 * Repository for locally cached credentials-independent app state.
 * Framework-free: the Android layer decides storage; this models the data.
 */
data class SessionConfig(
    val serverUrl: String,
    val username: String,
) {
    init {
        // http is accepted for local development only; release builds require
        // https and the transport helpers keep the scheme consistent.
        require(serverUrl.startsWith("https://") || serverUrl.startsWith("http://")) {
            "serverUrl must be http(s)"
        }
        require(username.isNotBlank()) { "username must not be blank" }
    }
}

/**
 * Conversation key derivation: deterministic, symmetric pair key so both
 * sides agree on the conversation id without a server round trip.
 */
fun conversationKey(userA: String, userB: String): String =
    listOf(userA, userB).sorted().joinToString(":", prefix = "dm:")

/** Local message cache entry statuses the UI can render. */
enum class LocalSendState { QUEUED, IN_FLIGHT, ACKNOWLEDGED, FAILED }

/**
 * Presence derivation per docs/adr/0003 and the server contract:
 * online only when the observed registration is fresh within the TTL,
 * offline on explicit unregister, unknown otherwise.
 */
fun effectivePresence(
    registered: Boolean?,
    observedAtEpochMs: Long?,
    nowEpochMs: Long,
    ttlMs: Long,
): PresenceStatus = when {
    observedAtEpochMs == null -> PresenceStatus.UNKNOWN
    registered != true -> PresenceStatus.OFFLINE
    nowEpochMs - observedAtEpochMs <= ttlMs -> PresenceStatus.ONLINE
    else -> PresenceStatus.UNKNOWN
}

/** Stable message cache id: server id when known, client key otherwise. */
fun cacheKeyFor(message: Message): String = if (message.id.isNotEmpty()) message.id else "client:${message.clientKey}"

/** Validation for the bounded message content contract. */
object MessageLimits {
    const val MAX_CONTENT_BYTES = 8192
    const val MAX_CONVERSATION_PAGE = 200

    fun validateContent(content: String): Boolean =
        content.isNotEmpty() && content.toByteArray(Charsets.UTF_8).size <= MAX_CONTENT_BYTES &&
            !content.contains('\u0000')
}
