package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Per-user conversation state (Phase 18): pin, mute and archive flags one
 * row per `(username, peer)`, created lazily on first update. Absent rows
 * mean all flags false, so contacts without state need no special casing.
 *
 * All devices of an account read the same rows, which is what makes the
 * chat list agree after a sign-in on a new device.
 */
data class ConversationStateRecord(
    val username: String,
    val peer: String,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val updatedAt: Long,
)

/** Request body of `PUT /v1/conversations/{peer}/state` (Phase 18). */
data class ConversationStateUpdate(
    val pinned: Boolean? = null,
    val muted: Boolean? = null,
    val archived: Boolean? = null,
)

class ConversationStates(private val db: JdbcClient) {

    companion object {
        /** WhatsApp-style cap on pinned conversations; 0 means no pin allowed. */
        const val MAX_PINNED = 5

        private const val SELECT_COLUMNS = "username, peer, pinned, muted, archived, updated_at"
    }

    private fun fromRow(rs: java.sql.ResultSet) = ConversationStateRecord(
        rs.getString("username"),
        rs.getString("peer"),
        rs.getBoolean("pinned"),
        rs.getBoolean("muted"),
        rs.getBoolean("archived"),
        rs.getLong("updated_at"),
    )

    /** All state rows of [username]; absent rows default to false. */
    fun all(username: String): List<ConversationStateRecord> =
        db.sql("SELECT $SELECT_COLUMNS FROM user_conversation_state WHERE username = :username")
            .param("username", username)
            .query { rs, _ -> fromRow(rs) }
            .list()

    fun find(username: String, peer: String): ConversationStateRecord? =
        db.sql("SELECT $SELECT_COLUMNS FROM user_conversation_state WHERE username = :username AND peer = :peer")
            .param("username", username).param("peer", peer)
            .query { rs, _ -> fromRow(rs) }
            .optional().orElse(null)

    /**
     * Applies [update] to the user's state for [peer], creating the row on
     * first touch (upsert). Only the provided flags change; `null` leaves
     * the flag as it is. Enforces the pin cap: pinning beyond
     * [MAX_PINNED] throws [IllegalArgumentException] (mapped to `400`).
     *
     * Returns the full state after the update.
     */
    fun update(username: String, peer: String, update: ConversationStateUpdate): ConversationStateRecord {
        val current = find(username, peer) ?: ConversationStateRecord(username, peer, false, false, false, 0L)
        val target = ConversationStateRecord(
            username = username,
            peer = peer,
            pinned = update.pinned ?: current.pinned,
            muted = update.muted ?: current.muted,
            archived = update.archived ?: current.archived,
            updatedAt = System.currentTimeMillis(),
        )
        if (target.pinned && !current.pinned) {
            val pinnedCount = all(username).count { it.pinned && it.peer != peer }
            require(pinnedCount < MAX_PINNED) { "at most $MAX_PINNED conversations can be pinned" }
        }
        db.sql(
            "INSERT INTO user_conversation_state(username, peer, pinned, muted, archived, updated_at) " +
                "VALUES(:username, :peer, :pinned, :muted, :archived, :updatedAt) " +
                "ON CONFLICT (username, peer) DO UPDATE SET pinned = :pinned, muted = :muted, " +
                "archived = :archived, updated_at = :updatedAt",
        ).param("username", username).param("peer", peer)
            .param("pinned", target.pinned).param("muted", target.muted).param("archived", target.archived)
            .param("updatedAt", target.updatedAt)
            .update()
        return target
    }

    /**
     * Un-archives [peer] for [username] when a new incoming message arrives
     * (WhatsApp behaviour, Phase 18). Applied on the send path so every
     * device converges; a missing row stays absent.
     */
    fun unarchive(username: String, peer: String) {
        db.sql(
            "UPDATE user_conversation_state SET archived = FALSE, updated_at = ? " +
                "WHERE username = ? AND peer = ? AND archived",
        ).param(System.currentTimeMillis()).params(username, peer).update()
    }
}
