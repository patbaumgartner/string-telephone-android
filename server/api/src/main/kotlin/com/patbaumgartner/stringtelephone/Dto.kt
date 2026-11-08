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
    /** The caller's pin/mute/archive state for this conversation (Phase 18). */
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
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
    /** Epoch ms at which the author last edited the message (Phase 17); null when never edited. */
    val editedAt: Long? = null,
    /** Group conversation this message belongs to (Phase 19); null for pairwise. */
    val groupId: String? = null,
    /**
     * How many group members have read this message (Phase 19); null for
     * pairwise messages, where the read receipt is a timestamp instead.
     */
    val readCount: Int? = null,
    /**
     * True when the message is end-to-end encrypted (ADR-0015, Phase 22):
     * `content` is then empty server-side and the plaintext is only inside
     * [envelope]. Legacy rows stay `false` and readable.
     */
    val encrypted: Boolean = false,
    /**
     * The caller's own ciphertext envelope for an encrypted message
     * (`"<type>:<base64>"`, type 2 = ratchet, 3 = prekey), personalized per
     * requester; null for plaintext or media messages.
     */
    val envelope: String? = null,
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
    /**
     * E2EE envelopes (ADR-0015, Phase 22): per-recipient ciphertext
     * `"<type>:<base64>"`. Mandatory for TEXT (the server never stores
     * plaintext text), keyed by recipient username — for a pairwise message
     * that is the peer *and* the sender (self-copy, so the author can read
     * their own history); for a group message every member including the
     * author. Optional for IMAGE/VOICE until Phase 23.
     */
    val envelopes: Map<String, String>? = null,
)

/** Marks the peer's messages read; `upToMessageId` bounds the mark to one point in the history. */
@kotlinx.serialization.Serializable
data class MarkReadRequest(val upToMessageId: String? = null)

/**
 * Sends one group message (Phase 19): no recipient — authorship comes from
 * the principal and visibility from the group membership.
 */
@kotlinx.serialization.Serializable
data class SendGroupMessageRequest(
    val content: String,
    val clientKey: String,
    /** Message this one replies to; must belong to the same group (ADR-0011). */
    val replyToId: String? = null,
    /** Message kind: TEXT (default), IMAGE or VOICE (Phase 21). */
    val type: String = "TEXT",
    /** Media id referenced by an IMAGE/VOICE message; must be an upload scoped to this group. */
    val mediaId: String? = null,
    /** Voice message duration in milliseconds (Phase 21); required for VOICE. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 21); required for VOICE. */
    val waveform: String? = null,
    /**
     * E2EE envelopes (ADR-0015, Phase 22): per-member ciphertext, one entry
     * per group member including the author. Mandatory for group TEXT.
     */
    val envelopes: Map<String, String>? = null,
)

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

/**
 * Inbound client frame on `/v1/ws`. Unlike the outbound [WsMessageDto], the
 * payload is a raw [kotlinx.serialization.json.JsonElement] because clients
 * send it either as an embedded object (what the app emits) or as a JSON
 * string; [WsHub.handleClientFrame] normalizes both forms (E2E 2026-11-08).
 */
@kotlinx.serialization.Serializable
data class WsClientEnvelopeDto(val type: String, val payload: kotlinx.serialization.json.JsonElement)

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
    /** Epoch ms at which the author last edited the message (Phase 17); null when never edited. */
    val editedAt: Long? = null,
    /** Group conversation this message belongs to (Phase 19); null for pairwise. */
    val groupId: String? = null,
    /** True when end-to-end encrypted (ADR-0015, Phase 22); `content` is then empty. */
    val encrypted: Boolean = false,
    /**
     * The receiving user's own ciphertext envelope for an encrypted message;
     * personalized per recipient on the fan-out, so each device gets exactly
     * its copy. Null for plaintext or media messages.
     */
    val envelope: String? = null,
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
data class WsTypingEvent(val peer: String, val typing: Boolean, val groupId: String? = null)

/** Incoming add/replace reaction: POST /v1/conversations/{peer}/messages/{id}/reactions */
@kotlinx.serialization.Serializable
data class AddReactionRequest(val emoji: String)

/** Outgoing reaction event: {"type":"message-reaction","payload":{...}} */
@kotlinx.serialization.Serializable
data class WsReactionEvent(
    val messageId: String,
    val peer: String,   // the conversation peer of the message (other than the author); "" for group events
    val authorUsername: String,
    val emoji: String,
    val action: String, // "added" or "removed"
    val groupId: String? = null, // set for group fan-out (Phase 19)
)

/** Deletion event pushed on /v1/ws as `{"type":"message-deleted"}` (ADR-0011, Phase 10). */
@kotlinx.serialization.Serializable
data class WsMessageDeletedEvent(
    val messageId: String,
    val peer: String,   // the conversation peer of the deleter; "" for group events
    val deletedBy: String,
    val deletedAt: Long,
    val groupId: String? = null, // set for group fan-out (Phase 19)
)

/** Per-member group read fan-out (Phase 19): `{"type":"group-message-read"}`. */
@kotlinx.serialization.Serializable
data class WsGroupMessageReadEvent(
    val groupId: String,
    /** Who read the messages; every member's devices converge the same counter. */
    val reader: String,
    val messageIds: List<String>,
    val readAt: Long,
)

/**
 * Re-stores the content of one message (Phase 17). For an encrypted message
 * (ADR-0015, Phase 22) `content` may be blank and the new ciphertext rides in
 * [envelopes] — keyed like on send (peer + author for pairwise), because the
 * server never sees the edited plaintext either.
 */
@kotlinx.serialization.Serializable
data class EditMessageRequest(
    val content: String = "",
    val envelopes: Map<String, String>? = null,
)

// ── Group chats (Phase 19, ADR-0012) ────────────────────────────────────────

/** Creates a group: name plus initial members; the creator becomes ADMIN. */
@kotlinx.serialization.Serializable
data class CreateGroupRequest(
    @field:jakarta.validation.constraints.NotBlank val name: String,
    val members: List<String> = emptyList(),
)

/** Renames a group (ADMIN only). */
@kotlinx.serialization.Serializable
data class RenameGroupRequest(
    @field:jakarta.validation.constraints.NotBlank val name: String,
)

/** Adds one member (ADMIN only). */
@kotlinx.serialization.Serializable
data class AddGroupMemberRequest(
    @field:jakarta.validation.constraints.NotBlank val username: String,
)

/** One group as the REST surface returns it, with its member list. */
@kotlinx.serialization.Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val creator: String,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<GroupMemberDto>,
)

@kotlinx.serialization.Serializable
data class GroupMemberDto(
    val username: String,
    val role: String,
    val joinedAt: Long,
)

/** Group message page — same shape as the pairwise `MessagesPageDto`. */
@kotlinx.serialization.Serializable
data class GroupMessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)

/** Marks group messages read up to a message id (Phase 19). */
@kotlinx.serialization.Serializable
data class MarkGroupReadRequest(val upToMessageId: String? = null)

/** Group typing signal from a client: {"type":"typing","payload":{"groupId":"...","typing":true}}. */
@kotlinx.serialization.Serializable
data class WsClientGroupTypingPayload(val groupId: String, val typing: Boolean)

// ── Group calls (Phase 20, ADR-0014) ────────────────────────────────────────

/** Starts (or returns) the group's call: the response names the bridge to dial. */
@kotlinx.serialization.Serializable
data class GroupCallDto(
    val bridgeId: String,
    val groupId: String,
    val starter: String,
    val withVideo: Boolean = false,
    val startedAt: Long,
)

@kotlinx.serialization.Serializable
data class StartGroupCallRequest(val withVideo: Boolean = false)

/** Realtime invite: ring every member except the starter. */
@kotlinx.serialization.Serializable
data class WsGroupCallInviteEvent(
    val groupId: String,
    val bridgeId: String,
    val starter: String,
    val withVideo: Boolean = false,
)

/** Realtime call state: `joined`, `left` or `ended`; `members` is who is on now. */
@kotlinx.serialization.Serializable
data class WsGroupCallStateEvent(
    val groupId: String,
    val bridgeId: String,
    val state: String,
    val by: String,
    val members: List<String> = emptyList(),
    val reason: String? = null,
)

/**
 * The caller's pin/mute/archive state for one conversation (Phase 18).
 * Returned by GET/PUT `/v1/conversations/{peer}/state`.
 */
@kotlinx.serialization.Serializable
data class ConversationStateDto(
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
)

/** Edit event pushed on /v1/ws as `{"type":"message-edited"}` (Phase 17). */
@kotlinx.serialization.Serializable
data class WsMessageEditedEvent(
    val messageId: String,
    val peer: String,   // the conversation peer of the message (other than the author)
    val content: String,
    val editedAt: Long,
    /**
     * The receiving user's ciphertext envelope when the message is encrypted
     * (ADR-0015, Phase 22); the fan-out personalizes this per recipient.
     * Null for plaintext edits.
     */
    val envelope: String? = null,
)

// ── E2EE key directory (ADR-0015, Phase 22) ─────────────────────────────────

/** A signed prekey as published and relayed: id + public key + identity signature (base64). */
@kotlinx.serialization.Serializable
data class SignedPreKeyDto(val id: Int, val publicKey: String, val signature: String)

/** The Kyber (PQXDH) last-resort prekey: id + public key + identity signature (base64). */
@kotlinx.serialization.Serializable
data class KyberPreKeyDto(val id: Int, val publicKey: String, val signature: String)

/** A one-time prekey: id + public key (base64); the signature is not needed for EC prekeys. */
@kotlinx.serialization.Serializable
data class OneTimePreKeyDto(val id: Int, val publicKey: String)

/**
 * The bundle a client publishes with `PUT /v1/keys`: identity key and
 * registration id, the current signed prekey, the Kyber last-resort prekey,
 * and a batch of fresh one-time prekeys (top-up; existing ids are ignored).
 */
@kotlinx.serialization.Serializable
data class KeyBundleDto(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

/**
 * Another user's published bundle as `GET /v1/keys/{peer}` returns it —
 * everything a sender needs to run X3DH/PQXDH, plus how many one-time
 * prekeys remain (fetch one via `GET /v1/keys/{peer}/one-time` before
 * establishing a session).
 */
@kotlinx.serialization.Serializable
data class PeerKeyBundleDto(
    val username: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePrekeyCount: Int,
)

/** The caller's own bundle as `GET /v1/keys/me` returns it (top-up signal). */
@kotlinx.serialization.Serializable
data class OwnKeyBundleDto(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePrekeyCount: Int,
)
