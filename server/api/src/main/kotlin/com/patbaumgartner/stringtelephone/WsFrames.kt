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
            WsPresenceEvent(username, snapshot.presence, snapshot.observedAtEpochMs),
        )))

    fun newMessage(message: MessageRecord): String =
        Json.encodeToString(WsMessageDto("new-message", Json.encodeToString(
            WsNewMessageEvent(message.id, message.sender, message.recipient, message.content, message.createdAt, message.clientKey, message.type, message.mediaId, message.width, message.height, message.replyTo),
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
}
