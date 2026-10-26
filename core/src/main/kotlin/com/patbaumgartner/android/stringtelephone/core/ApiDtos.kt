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
    /** Message kind: TEXT (default) or IMAGE (Phase 6). */
    val type: String = "TEXT",
    /** Media id referenced by an IMAGE message; null for text. */
    val mediaId: String? = null,
    /** Decoded image width in pixels for IMAGE messages. */
    val width: Int? = null,
    /** Decoded image height in pixels for IMAGE messages. */
    val height: Int? = null,
    /** Reactions on this message (ADR-0011, Phase 8). */
    val reactions: List<ReactionDto>? = null,
    /** Message this one replies to; null when not a reply (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Tombstone (ADR-0011, Phase 10): non-null once deleted for both sides. */
    val deletedAt: Long? = null,
    /** Who deleted the message; null while it exists. */
    val deletedBy: String? = null,
    /** Voice message duration in milliseconds (Phase 11); null for non-voice messages. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
    val waveform: String? = null,
)

/** One emoji reaction: who reacted and when (ADR-0011, Phase 8). */
@Serializable
data class ReactionDto(
    val authorUsername: String,
    val emoji: String,
    val createdAt: Long,
)

@Serializable
data class SendMessageRequest(
    val recipient: String,
    val content: String = "",
    val clientKey: String,
    val type: String = "TEXT",
    val mediaId: String? = null,
    /** Message this one replies to; must be in the same conversation (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Voice message duration in milliseconds (Phase 11); required for VOICE. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); required for VOICE. */
    val waveform: String? = null,
)

@Serializable
data class MessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)

@Serializable
data class MediaRecordDto(
    val id: String,
    val owner: String,
    val peer: String,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val createdAt: Long,
    val width: Int? = null,
    val height: Int? = null,
)

/** Marks the peer's messages read; `upToMessageId` bounds the mark to one point in the history. */
@Serializable
data class MarkReadRequest(val upToMessageId: String? = null)

@Serializable
data class MarkReadResponseDto(val messageIds: List<String> = emptyList(), val readAt: Long = 0)

/** Adds or replaces the caller's reaction on a message (ADR-0011, Phase 8). */
@Serializable
data class SetReactionRequest(val emoji: String)

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
    val type: String = "TEXT",
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Message this one replies to; null when not a reply (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Tombstone (ADR-0011, Phase 10): non-null once deleted for both sides. */
    val deletedAt: Long? = null,
    /** Who deleted the message; null while it exists. */
    val deletedBy: String? = null,
    /** Voice message duration in milliseconds (Phase 11); null for non-voice messages. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
    val waveform: String? = null,
)

@Serializable
data class WsPresenceDto(val username: String, val presence: String, val updatedAt: Long? = null)

@Serializable
data class WsMessageReadDto(val messageIds: List<String> = emptyList(), val readAt: Long = 0)

/** Typing indicator event payload: peer started or stopped composing (Phase 7). */
@Serializable
data class WsTypingDto(val peer: String, val typing: Boolean)

/** Reaction event payload (ADR-0011, Phase 8). */
@Serializable
data class WsReactionDto(
    val messageId: String,
    val peer: String,
    val authorUsername: String,
    val emoji: String,
    val action: String, // "added" or "removed"
)

/** Deletion tombstone event payload (ADR-0011, Phase 10). */
@Serializable
data class WsMessageDeletedDto(
    val messageId: String,
    val peer: String,
    val deletedBy: String,
    val deletedAt: Long,
)

/** Decoded realtime frame; unknown types are reported, malformed frames dropped. */
sealed interface RealtimeEvent {
    data class NewMessage(
        val id: String,
        val sender: String,
        val recipient: String,
        val content: String,
        val createdAtEpochMs: Long,
        val clientKey: String,
        val type: String = "TEXT",
        val mediaId: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        /** Message this one replies to; null when not a reply (ADR-0011, Phase 9). */
        val replyToId: String? = null,
        /** Voice message duration in milliseconds (Phase 11); null for non-voice messages. */
        val durationMs: Int? = null,
        /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
        val waveform: String? = null,
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

    /** Typing indicator (Phase 7): the peer started or stopped composing. */
    data class Typing(
        val peer: String,
        val typing: Boolean,
    ) : RealtimeEvent

    /**
     * Reaction change (ADR-0011, Phase 8): the peer reacted to a message or
     * removed their reaction. The [authorUsername] is who changed it, so the
     * local device can tell its own reactions apart from the peer's.
     */
    data class MessageReaction(
        val messageId: String,
        val peer: String,         // the other participant (not the author)
        val authorUsername: String,
        val emoji: String,
        val added: Boolean,
    ) : RealtimeEvent

    /**
     * Deletion tombstone (ADR-0011, Phase 10): a message was deleted for
     * both sides. [deletedBy] is who deleted it, so the local device can
     * tell its own action apart from the peer's.
     */
    data class MessageDeleted(
        val messageId: String,
        val peer: String,
        val deletedBy: String,
        val deletedAtEpochMs: Long,
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
                type = event.type,
                mediaId = event.mediaId,
                width = event.width,
                height = event.height,
                replyToId = event.replyToId,
                durationMs = event.durationMs,
                waveform = event.waveform,
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
        "typing" -> runCatching {
            val event = realtimeJson.decodeFromString(WsTypingDto.serializer(), envelope.payload)
            RealtimeEvent.Typing(event.peer, event.typing)
        }.getOrNull()
        "message-reaction" -> runCatching {
            val event = realtimeJson.decodeFromString(WsReactionDto.serializer(), envelope.payload)
            RealtimeEvent.MessageReaction(
                messageId = event.messageId,
                peer = event.peer,
                authorUsername = event.authorUsername,
                emoji = event.emoji,
                added = event.action == "added",
            )
        }.getOrNull()
        "message-deleted" -> runCatching {
            val event = realtimeJson.decodeFromString(WsMessageDeletedDto.serializer(), envelope.payload)
            RealtimeEvent.MessageDeleted(
                messageId = event.messageId,
                peer = event.peer,
                deletedBy = event.deletedBy,
                deletedAtEpochMs = event.deletedAt,
            )
        }.getOrNull()
        else -> RealtimeEvent.Unknown(envelope.type)
    }
}

private val realtimeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
