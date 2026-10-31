package com.patbaumgartner.stringtelephone

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The frames the server pushes on `/v1/ws`. Every frame is an envelope of
 * `{"type": ..., "payload": "<json>"}`, so the app can dispatch on the type
 * and decode the payload separately.
 */
object WsFrames {

    fun presence(username: String, snapshot: PresenceSnapshot): String =
        Json.encodeToString(WsMessageDto("presence", Json.encodeToString(
            WsPresenceEvent(username, snapshot.presence, snapshot.observedAtEpochMs, snapshot.lastSeenAtEpochMs),
        )))

    fun newMessage(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("new-message", Json.encodeToString(
            WsNewMessageEvent(message.id, message.sender, message.recipient, message.content, message.createdAt, message.clientKey, message.type, message.mediaId, message.width, message.height, message.replyTo, message.deletedAt, message.deletedBy, message.durationMs, message.waveform, message.editedAt),
        )))

    /**
     * Group message fan-out (Phase 19): pushed to every member when a group
     * message is stored. Mirrors `new-message` with a `groupId` field;
     * `recipient` stays empty in the payload (ADR-0012 constraint 3 — the
     * event names its conversation explicitly).
     */
    fun groupNewMessage(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("group-message", Json.encodeToString(
            WsNewMessageEvent(message.id, message.sender, message.recipient, message.content, message.createdAt, message.clientKey, message.type, message.mediaId, message.width, message.height, message.replyTo, message.deletedAt, message.deletedBy, message.durationMs, message.waveform, message.editedAt, message.groupId),
        )))

    /** Per-member group read fan-out (Phase 19): each member sees only their own reads. */
    fun groupMessageRead(groupId: String, reader: String, mark: ReadMark): String =
        Json.encodeToString(WsMessageDto("group-message-read", Json.encodeToString(
            WsGroupMessageReadEvent(groupId, reader, mark.messageIds, mark.readAt),
        )))

    /** Group reaction change (Phase 19): mirrors `message-reaction` with a `groupId`. */
    fun groupReaction(message: MessageRecord, author: String, emoji: String, added: Boolean): String =
        Json.encodeToString(WsMessageDto("group-reaction", Json.encodeToString(
            WsReactionEvent(
                messageId = message.id,
                peer = "",
                authorUsername = author,
                emoji = emoji,
                action = if (added) "added" else "removed",
                groupId = message.groupId,
            ),
        )))

    /** Group deletion tombstone (Phase 19): mirrors `message-deleted` with a `groupId`. */
    fun groupMessageDeleted(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("group-deleted", Json.encodeToString(
            WsMessageDeletedEvent(
                messageId = message.id,
                peer = "",
                deletedBy = message.deletedBy ?: "",
                deletedAt = message.deletedAt ?: 0L,
                groupId = message.groupId,
            ),
        )))

    /** Group typing variant (Phase 19): the composer started or stopped in a group. */
    fun groupTyping(groupId: String, from: String, typing: Boolean): String =
        Json.encodeToString(WsMessageDto("typing", Json.encodeToString(
            WsTypingEvent(from, typing, groupId),
        )))

    /** Read receipt (ADR-0010): the reader opened these messages in the conversation. */
    fun messageRead(mark: ReadMark): String =
        Json.encodeToString(WsMessageDto("message-read", Json.encodeToString(
            WsMessageReadEvent(mark.messageIds, mark.readAt),
        )))

    /** Typing indicator (Phase 7): the sender started or stopped composing. */
    fun typing(peer: String, typing: Boolean): String =
        Json.encodeToString(WsMessageDto("typing", Json.encodeToString(
            WsTypingEvent(peer, typing),
        )))

    /**
     * Reaction change (ADR-0011, Phase 8). Both conversation participants
     * learn about the change; [author] is who reacted, so the other side can
     * tell its own reactions apart.
     */
    fun reaction(message: MessageRecord, author: String, emoji: String, added: Boolean): String =
        Json.encodeToString(WsMessageDto("message-reaction", Json.encodeToString(
            WsReactionEvent(
                messageId = message.id,
                peer = if (message.sender == author) message.recipient else message.sender,
                authorUsername = author,
                emoji = emoji,
                action = if (added) "added" else "removed",
            ),
        )))

    /**
     * Deletion tombstone (ADR-0011, Phase 10). Both participants learn the
     * message was deleted; [deletedBy] is who deleted it, so each device can
     * tell its own action apart from the peer's.
     */
    fun messageDeleted(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("message-deleted", Json.encodeToString(
            WsMessageDeletedEvent(
                messageId = message.id,
                peer = if (message.sender == message.deletedBy) message.recipient else message.sender,
                deletedBy = message.deletedBy ?: "",
                deletedAt = message.deletedAt ?: 0L,
            ),
        )))

    /**
     * Edited content (Phase 17). Both participants receive the frame; the
     * payload carries the new content so the peer's devices re-render the
     * bubble without a history refresh, mirroring `message-deleted`. Only
     * the author may edit, so the peer is always the recipient.
     */
    fun messageEdited(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("message-edited", Json.encodeToString(
            WsMessageEditedEvent(
                messageId = message.id,
                peer = message.recipient,
                content = message.content,
                editedAt = message.editedAt ?: 0L,
            ),
        )))
}
