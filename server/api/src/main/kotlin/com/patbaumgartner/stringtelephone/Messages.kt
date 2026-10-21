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
)

/** Result of marking a conversation read: the affected ids and the read timestamp. */
data class ReadMark(val messageIds: List<String>, val readAt: Long)

class Messages(
    private val db: JdbcClient,
    private val onNewMessage: (MessageRecord) -> Unit = {},
) {

    companion object {
        const val MAX_CONTENT_BYTES = 8192
        const val MAX_PAGE = 200
    }

    private fun fromRow(rs: java.sql.ResultSet): MessageRecord = MessageRecord(
        rs.getString("id"),
        rs.getString("sender"),
        rs.getString("recipient"),
        rs.getString("content"),
        rs.getLong("created_at"),
        rs.getString("client_key"),
        rs.getLong("read_at").let { if (rs.wasNull()) null else it },
    )

    // A single INSERT ... ON CONFLICT DO NOTHING is atomic by itself; the
    // read-your-writes lookup below runs after it commits, exactly as before.
    // Values are bound with params(), never concatenated into the SQL text.
    fun insert(sender: String, recipient: String, content: String, clientKey: String): MessageRecord {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(content.isNotEmpty()) { "content must not be empty" }
        require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",
        ).params(id, sender, recipient, content, now, clientKey).update()
        val stored = find(sender, recipient, clientKey)
            ?: error("stored message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    fun page(sender: String, recipient: String, before: Pair<Long, String>?, limit: Int): List<MessageRecord> {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE)
        val columns = "id, sender, recipient, content, created_at, client_key, read_at"
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
            "SELECT id, sender, recipient, content, created_at, client_key, read_at FROM messages " +
                "WHERE sender = ? AND recipient = ? AND client_key = ?",
        ).params(sender, recipient, clientKey).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    fun findById(id: String): MessageRecord? =
        db.sql(
            "SELECT id, sender, recipient, content, created_at, client_key, read_at FROM messages WHERE id = ?",
        ).param(id).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

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
}
