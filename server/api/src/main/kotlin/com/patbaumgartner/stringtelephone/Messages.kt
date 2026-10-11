package com.patbaumgartner.stringtelephone

data class MessageRecord(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)

class Messages(
    private val db: Database,
    private val onNewMessage: (MessageRecord) -> Unit = {},
) {

    companion object {
        const val MAX_CONTENT_BYTES = 8192
        const val MAX_PAGE = 200
    }


    fun insert(sender: String, recipient: String, content: String, clientKey: String): MessageRecord {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(content.isNotEmpty()) { "content must not be empty" }
        require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.tx { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO messages(id, sender, recipient, content, created_at, client_key) VALUES(?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, sender)
                ps.setString(3, recipient)
                ps.setString(4, content)
                ps.setLong(5, now)
                ps.setString(6, clientKey)
                ps.executeUpdate()
            }
        }
        val stored = find(sender, recipient, clientKey)!!
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    fun page(sender: String, recipient: String, before: Long?, limit: Int): List<MessageRecord> = db.read { conn ->
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE)
        val sql = if (before == null) {
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
                "WHERE (sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
                "WHERE ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) AND created_at < ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, sender)
            ps.setString(2, recipient)
            ps.setString(3, recipient)
            ps.setString(4, sender)
            var idx = 5
            if (before != null) {
                ps.setLong(idx++, before)
            }
            ps.setInt(idx, effectiveLimit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<MessageRecord>()
                while (rs.next()) {
                    out += fromRow(rs)
                }
                out
            }
        }
    }

    fun find(sender: String, recipient: String, clientKey: String): MessageRecord? = db.read { conn ->
        conn.prepareStatement(
            "SELECT id, sender, recipient, content, created_at, client_key FROM messages " +
                "WHERE sender = ? AND recipient = ? AND client_key = ?",
        ).use { ps ->
            ps.setString(1, sender)
            ps.setString(2, recipient)
            ps.setString(3, clientKey)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@read null
                fromRow(rs)
            }
        }
    }

    private fun fromRow(rs: java.sql.ResultSet): MessageRecord = MessageRecord(
        rs.getString("id"),
        rs.getString("sender"),
        rs.getString("recipient"),
        rs.getString("content"),
        rs.getLong("created_at"),
        rs.getString("client_key"),
    )
}
