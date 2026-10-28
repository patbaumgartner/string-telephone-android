package com.patbaumgartner.stringtelephone

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(val username: String, val displayName: String)

@Serializable
data class ContactDto(
    val username: String,
    val displayName: String,
    val presence: String,
    val presenceUpdatedAtEpochMs: Long?,
    /** When the user was last reachable; null while online or never connected (Phase 13). */
    val lastSeenEpochMs: Long? = null,
)

@kotlinx.serialization.Serializable
data class ContactsDto(val contacts: List<ContactDto>)

@kotlinx.serialization.Serializable
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

@kotlinx.serialization.Serializable
data class SendMessageRequest(
    @field:jakarta.validation.constraints.NotBlank val recipient: String,
    val content: String = "",
    val clientKey: String,
    /** TEXT (default), IMAGE or VOICE. An IMAGE/VOICE message references [mediaId]. */
    val type: String = "TEXT",
    val mediaId: String? = null,
    /** Message this one replies to; must be in the same conversation (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Voice message duration in milliseconds (Phase 11); required for VOICE. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); required for VOICE. */
    val waveform: String? = null,
)

/** Marks the peer's messages read; `upToMessageId` bounds the mark to one point in the history. */
@kotlinx.serialization.Serializable
data class MarkReadRequest(val upToMessageId: String? = null)

@kotlinx.serialization.Serializable
data class MarkReadResponse(val messageIds: List<String>, val readAt: Long)

@kotlinx.serialization.Serializable
data class MessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)

@kotlinx.serialization.Serializable
data class CreateUserRequest(val username: String, val displayName: String)

@kotlinx.serialization.Serializable
data class CreateResponse(val username: String, val displayName: String, val apiToken: String)

@kotlinx.serialization.Serializable
data class RevokeRequest(val username: String)

@kotlinx.serialization.Serializable
data class RevokeResponse(val username: String, val revoked: Boolean)

@kotlinx.serialization.Serializable
data class UserListDto(val users: List<UserDto>)

@kotlinx.serialization.Serializable
data class UserDto(val username: String, val displayName: String, val active: Boolean)

@kotlinx.serialization.Serializable
data class ErrorDto(val error: String)

@kotlinx.serialization.Serializable
data class WsMessageDto(val type: String, val payload: String)

/** Presence change pushed on /v1/ws as `{"type":"presence"}`. */
@kotlinx.serialization.Serializable
data class WsPresenceEvent(
    val username: String,
    val presence: String,
    val updatedAt: Long?,
    /** When the user was last reachable; null while online or never connected (Phase 13). */
    val lastSeenAt: Long? = null,
)

/** WebSocket event frames pushed on /v1/ws. */
@kotlinx.serialization.Serializable
data class WsNewMessageEvent(
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

/** Read-receipt event pushed on /v1/ws as `{"type":"message-read"}` (ADR-0010). */
@kotlinx.serialization.Serializable
data class WsMessageReadEvent(
    val messageIds: List<String>,
    val readAt: Long,
)

/** Incoming typing signal from a client: {"type":"typing","payload":{"typing":true}}. */
@kotlinx.serialization.Serializable
data class WsClientTypingPayload(val typing: Boolean)

/** Outgoing typing signal to the peer: {"type":"typing","payload":{"peer":"alice","typing":true}}. */
@kotlinx.serialization.Serializable
data class WsTypingEvent(val peer: String, val typing: Boolean)

/** Incoming add/replace reaction: POST /v1/conversations/{peer}/messages/{id}/reactions */
@kotlinx.serialization.Serializable
data class AddReactionRequest(val emoji: String)

/** Outgoing reaction event: {"type":"message-reaction","payload":{...}} */
@kotlinx.serialization.Serializable
data class WsReactionEvent(
    val messageId: String,
    val peer: String,   // the conversation peer of the message (other than the author)
    val authorUsername: String,
    val emoji: String,
    val action: String, // "added" or "removed"
)

/** Deletion event pushed on /v1/ws as `{"type":"message-deleted"}` (ADR-0011, Phase 10). */
@kotlinx.serialization.Serializable
data class WsMessageDeletedEvent(
    val messageId: String,
    val peer: String,   // the conversation peer of the deleter
    val deletedBy: String,
    val deletedAt: Long,
)
