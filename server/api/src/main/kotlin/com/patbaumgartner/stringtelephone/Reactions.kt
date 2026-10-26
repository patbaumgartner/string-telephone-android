package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Reaction CRUD (ADR-0011, Phase 8). One row per (message, author); reacting
 * again replaces the emoji (upsert). Deleting removes the row.
 *
 * Every change fans out over [hub] to both conversation participants, so each
 * device can update chips immediately without a poll.
 */
class Reactions(
    private val db: JdbcClient,
    private val messages: Messages,
    private val hub: WsHub,
) {

    /**
     * Adds or replaces a reaction. Validates that the message exists and that
     * the author is a participant in the conversation.
     *
     * Returns the list of all reactions on the message after the change, so the
     * REST response gives the client everything it needs without a separate
     * history refresh.
     */
    fun addReaction(messageId: String, author: String, emoji: String): List<ReactionDto> {
        val message = messages.findById(messageId)
            ?: throw IllegalArgumentException("message not found")
        require(author == message.sender || author == message.recipient) {
            "author must be a participant"
        }
        val now = System.currentTimeMillis()
        db.sql(
            "INSERT INTO reactions(message_id, author_username, emoji, created_at) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (message_id, author_username) DO UPDATE SET emoji = excluded.emoji, created_at = excluded.created_at",
        ).params(messageId, author, emoji, now).update()
        hub.broadcastReaction(message, author, emoji, added = true)
        return listForMessage(messageId)
    }

    /** Removes the author's reaction. Idempotent: a missing row is a no-op. */
    fun removeReaction(messageId: String, author: String): List<ReactionDto> {
        val message = messages.findById(messageId) ?: return emptyList()
        require(author == message.sender || author == message.recipient) {
            "author must be a participant"
        }
        // Capture the emoji before deleting so the "removed" fan-out carries it.
        val previous = db.sql("SELECT emoji FROM reactions WHERE message_id = ? AND author_username = ?")
            .params(messageId, author).query(String::class.java).optional().orElse(null)
        val deleted = db.sql(
            "DELETE FROM reactions WHERE message_id = ? AND author_username = ?",
        ).params(messageId, author).update()
        if (deleted > 0) {
            hub.broadcastReaction(message, author, previous ?: "", added = false)
        }
        return listForMessage(messageId)
    }

    /** All reactions on a message, for the history response. */
    fun listForMessage(messageId: String): List<ReactionDto> =
        db.sql("SELECT message_id, author_username, emoji, created_at FROM reactions WHERE message_id = ? ORDER BY created_at")
            .param(messageId)
            .query { rs, _ ->
                ReactionDto(rs.getString("author_username"), rs.getString("emoji"), rs.getLong("created_at"))
            }.list()

    /** All reactions on a set of messages, for bulk-loading the history response. */
    fun listForMessages(messageIds: List<String>): Map<String, List<ReactionDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        val placeholders = messageIds.indices.joinToString(",") { "?" }
        return db.sql("SELECT message_id, author_username, emoji, created_at FROM reactions WHERE message_id IN ($placeholders) ORDER BY created_at")
            .params(*messageIds.toTypedArray())
            .query { rs, _ ->
                ReactionDto(rs.getString("author_username"), rs.getString("emoji"), rs.getLong("created_at")) to rs.getString("message_id")
            }.list()
            .groupBy({ it.second }, { it.first })
    }
}

@kotlinx.serialization.Serializable
data class ReactionDto(val authorUsername: String, val emoji: String, val createdAt: Long)
