package com.patbaumgartner.android.stringtelephone.core

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(val username: String, val displayName: String)

@Serializable
data class ContactDto(
    val username: String,
    val displayName: String,
    val presence: String,
    val presenceUpdatedAtEpochMs: Long? = null,
)

@Serializable
data class ContactsDto(val contacts: List<ContactDto>)

@Serializable
data class MessageDto(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
    /** Epoch millisecond at which the recipient read the message; null when unread (ADR-0010). */
    val readAt: Long? = null,
)

@Serializable
data class SendMessageRequest(val recipient: String, val content: String, val clientKey: String)

@Serializable
data class MessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)

/** Marks the peer's messages read; `upToMessageId` bounds the mark to one point in the history. */
@Serializable
data class MarkReadRequest(val upToMessageId: String? = null)

@Serializable
data class MarkReadResponseDto(val messageIds: List<String> = emptyList(), val readAt: Long = 0)

/** Envelope wrapping every frame the companion API pushes on `/v1/ws`. */
@Serializable
data class WsEnvelopeDto(val type: String, val payload: String)

@Serializable
data class WsNewMessageDto(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)

@Serializable
data class WsPresenceDto(val username: String, val presence: String, val updatedAt: Long? = null)

@Serializable
data class WsMessageReadDto(val messageIds: List<String> = emptyList(), val readAt: Long = 0)

/** Decoded realtime frame; unknown types are reported, malformed frames dropped. */
sealed interface RealtimeEvent {
    data class NewMessage(
        val id: String,
        val sender: String,
        val recipient: String,
        val content: String,
        val createdAtEpochMs: Long,
        val clientKey: String,
    ) : RealtimeEvent

    data class Presence(
        val username: String,
        val presence: String,
        val updatedAtEpochMs: Long?,
    ) : RealtimeEvent

    /** Read receipts (ADR-0010): the peer read the listed message ids. */
    data class MessageRead(
        val messageIds: List<String>,
        val readAtEpochMs: Long,
    ) : RealtimeEvent

    data class Unknown(val type: String) : RealtimeEvent
}

/**
 * Parses one text frame from `/v1/ws` into a [RealtimeEvent]; returns null for
 * frames that are not a valid envelope so a bad frame never breaks the stream.
 */
fun parseRealtimeEvent(frame: String): RealtimeEvent? {
    val envelope = runCatching {
        realtimeJson.decodeFromString(WsEnvelopeDto.serializer(), frame)
    }.getOrNull() ?: return null
    return when (envelope.type) {
        "new-message" -> runCatching {
            val event = realtimeJson.decodeFromString(WsNewMessageDto.serializer(), envelope.payload)
            RealtimeEvent.NewMessage(
                id = event.id,
                sender = event.sender,
                recipient = event.recipient,
                content = event.content,
                createdAtEpochMs = event.createdAt,
                clientKey = event.clientKey,
            )
        }.getOrNull()
        "presence" -> runCatching {
            val event = realtimeJson.decodeFromString(WsPresenceDto.serializer(), envelope.payload)
            RealtimeEvent.Presence(event.username, event.presence, event.updatedAt)
        }.getOrNull()
        "message-read" -> runCatching {
            val event = realtimeJson.decodeFromString(WsMessageReadDto.serializer(), envelope.payload)
            RealtimeEvent.MessageRead(event.messageIds, event.readAt)
        }.getOrNull()
        else -> RealtimeEvent.Unknown(envelope.type)
    }
}

private val realtimeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
