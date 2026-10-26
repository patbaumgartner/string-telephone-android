package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

data class MessageRecord(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
    val readAt: Long? = null,
    val type: String = "TEXT",
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Message this one replies to; null when the message is not a reply (ADR-0011, Phase 9). */
    val replyTo: String? = null,
    /** Tombstone (ADR-0011, Phase 10): non-null once deleted for both sides. */
    val deletedAt: Long? = null,
    /** Who deleted the message for both sides (ADR-0011, Phase 10). */
    val deletedBy: String? = null,
    /** Voice length in milliseconds (Phase 11); null for non-voice messages. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
    val waveform: String? = null,
) {
    /** True when the message is a tombstone; the content is cleared server-side. */
    val deleted: Boolean get() = deletedAt != null
}

/** Result of marking a conversation read: the affected ids and the read timestamp. */
data class ReadMark(val messageIds: List<String>, val readAt: Long)

class Messages(
    private val db: JdbcClient,
    private val onNewMessage: (MessageRecord) -> Unit = {},
) {

    companion object {
        const val MAX_CONTENT_BYTES = 8192
        const val MAX_PAGE = 200

        /** Every persisted column, in [fromRow] order. */
        private const val SELECT_COLUMNS =
            "id, sender, recipient, content, created_at, client_key, read_at, type, media_id, width, height, reply_to, deleted_at, deleted_by, duration_ms, waveform"
    }

    private fun fromRow(rs: java.sql.ResultSet): MessageRecord = MessageRecord(
        rs.getString("id"),
        rs.getString("sender"),
        rs.getString("recipient"),
        rs.getString("content"),
        rs.getLong("created_at"),
        rs.getString("client_key"),
        rs.getLong("read_at").let { if (rs.wasNull()) null else it },
        rs.getString("type") ?: "TEXT",
        rs.getString("media_id"),
        rs.getObject("width", Integer::class.java)?.toInt()?.takeIf { it > 0 },
        rs.getObject("height", Integer::class.java)?.toInt()?.takeIf { it > 0 },
        rs.getString("reply_to"),
        rs.getLong("deleted_at").let { if (rs.wasNull()) null else it },
        rs.getString("deleted_by"),
        rs.getInt("duration_ms").let { if (rs.wasNull()) null else it },
        rs.getString("waveform"),
    )

    // A single INSERT ... ON CONFLICT DO NOTHING is atomic by itself; the
    // read-your-writes lookup below runs after it commits, exactly as before.
    // Values are bound with params(), never concatenated into the SQL text.
    fun insert(
        sender: String,
        recipient: String,
        content: String,
        clientKey: String,
        replyTo: String? = null,
    ): MessageRecord {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(content.isNotEmpty()) { "content must not be empty" }
        require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, reply_to) VALUES(:id, :sender, :recipient, :content, :createdAt, :clientKey, :type, :replyTo) ON CONFLICT DO NOTHING",
        ).param("id", id).param("sender", sender).param("recipient", recipient).param("content", content)
            .param("createdAt", now).param("clientKey", clientKey).param("type", "TEXT").param("replyTo", replyTo)
            .update()
        val stored = find(sender, recipient, clientKey)
            ?: error("stored message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    fun insertMedia(
        sender: String,
        recipient: String,
        clientKey: String,
        mediaId: String,
        media: MediaRecord,
        replyTo: String? = null,
    ): MessageRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, media_id, width, height, reply_to) VALUES(:id, :sender, :recipient, '', :createdAt, :clientKey, :type, :mediaId, :width, :height, :replyTo) ON CONFLICT DO NOTHING",
        ).param("id", id).param("sender", sender).param("recipient", recipient)
            .param("createdAt", now).param("clientKey", clientKey).param("type", "IMAGE")
            .param("replyTo", replyTo).update()
        val stored = find(sender, recipient, clientKey)
            ?: error("stored message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    /**
     * Stores a VOICE message (Phase 11): a media reference plus the recorded
     * duration and waveform so both clients render the player identically.
     */
    fun insertVoice(
        sender: String,
        recipient: String,
        clientKey: String,
        mediaId: String,
        durationMs: Int,
        waveform: String,
        replyTo: String? = null,
    ): MessageRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, media_id, duration_ms, waveform, reply_to) " +
                "VALUES(:id, :sender, :recipient, '', :createdAt, :clientKey, :type, :mediaId, :durationMs, :waveform, :replyTo) ON CONFLICT DO NOTHING",
        ).param("id", id).param("sender", sender).param("recipient", recipient)
            .param("createdAt", now).param("clientKey", clientKey).param("type", "VOICE")
            .param("mediaId", mediaId).param("durationMs", durationMs).param("waveform", waveform)
            .param("replyTo", replyTo).update()
        val stored = find(sender, recipient, clientKey)
            ?: error("stored message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    fun page(sender: String, recipient: String, before: Pair<Long, String>?, limit: Int): List<MessageRecord> {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE)
        val columns = SELECT_COLUMNS
        val sql = if (before == null) {
            "SELECT $columns FROM messages " +
                "WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT $columns FROM messages " +
                "WHERE ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) " +
                "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        }
        var statement = db.sql(sql).params(sender, recipient, recipient, sender)
        if (before != null) statement = statement.param(before.first).param(before.first).param(before.second)
        return statement.param(effectiveLimit).query { rs, _ -> fromRow(rs) }.list()
    }

    fun find(sender: String, recipient: String, clientKey: String): MessageRecord? =
        db.sql(
            "SELECT $SELECT_COLUMNS FROM messages " +
                "WHERE sender = ? AND recipient = ? AND client_key = ?",
        ).params(sender, recipient, clientKey).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    fun findById(id: String): MessageRecord? =
        db.sql(
            "SELECT $SELECT_COLUMNS FROM messages WHERE id = ?",
        ).param(id).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    /**
     * Distinct conversation partners of [user] in both directions, most
     * recently active first. The typing fan-out (Phase 7) uses this to reach
     * the peers who actually share a conversation with the sender.
     */
    fun recentPeers(user: String, limit: Int = 50): List<String> =
        db.sql(
            "SELECT peer FROM (" +
                "SELECT recipient AS peer, MAX(created_at) AS latest FROM messages WHERE sender = :user GROUP BY recipient " +
                "UNION ALL " +
                "SELECT sender AS peer, MAX(created_at) AS latest FROM messages WHERE recipient = :user GROUP BY sender" +
                ") recent WHERE peer <> :user GROUP BY peer ORDER BY MAX(latest) DESC LIMIT :limit",
        ).param("user", user).param("limit", limit).query(String::class.java).list().filterNotNull()

    /**
     * Marks the peer's messages as read by [reader] (ADR-0010). Only the
     * recipient may mark a message read, and only the peer's messages are
     * affected. When [upTo] is given, only messages up to and including it
     * are marked; it must be one of the peer's messages in this conversation.
     *
     * The update is idempotent: already-read messages keep their original
     * timestamp, so a repeated mark does not move the receipt.
     */
    fun markRead(reader: String, peer: String, upTo: Pair<Long, String>?): ReadMark {
        val now = System.currentTimeMillis()
        // The CTE keeps the update atomic and still returns the affected ids
        // in conversation order, so the response is stable for the client.
        val statement = db.sql(
            "WITH marked AS (" +
                "UPDATE messages SET read_at = ? WHERE sender = ? AND recipient = ? AND read_at IS NULL " +
                (if (upTo == null) "" else "AND (created_at < ? OR (created_at = ? AND id <= ?)) ") +
                "RETURNING id, created_at" +
                ") SELECT id FROM marked ORDER BY created_at, id",
        )
        var bound = statement.param(now).params(peer, reader)
        if (upTo != null) bound = bound.param(upTo.first).param(upTo.first).param(upTo.second)
        val ids = bound.query(String::class.java).list().filterNotNull()
        return ReadMark(ids, now)
    }

    /**
     * Tombstones a message for both sides (ADR-0011, Phase 10): clears the
     * content, stamps `deleted_at`/`deleted_by`, and keeps the row so sync
     * and pagination stay stable. Returns the tombstoned record, or null
     * when the message does not exist.
     *
     * Idempotent: deleting an already-deleted message keeps the first
     * tombstone, so a repeated request never moves the timestamp.
     */
    fun delete(messageId: String, deletedBy: String): MessageRecord? {
        val now = System.currentTimeMillis()
        db.sql(
            "UPDATE messages SET content = '', deleted_at = ?, deleted_by = ? " +
                "WHERE id = ? AND deleted_at IS NULL",
        ).params(now, deletedBy, messageId).update()
        return findById(messageId)
    }
}
