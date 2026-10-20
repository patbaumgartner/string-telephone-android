package com.patbaumgartner.stringtelephone

import org.springframework.jdbc.core.simple.JdbcClient

data class MessageRecord(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)

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
        val sql = if (before == null) {
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
                "WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
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
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
                "WHERE sender = ? AND recipient = ? AND client_key = ?",
        ).params(sender, recipient, clientKey).query { rs, _ -> fromRow(rs) }.optional().orElse(null)
}
