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
    /** Epoch ms at which the author last re-stored the content (Phase 17); null when never edited. */
    val editedAt: Long? = null,
    /** Group this message belongs to (Phase 19); null for pairwise messages. */
    val groupId: String? = null,
    /**
     * True when the message is end-to-end encrypted (ADR-0015, Phase 22):
     * `content` is stored empty and the per-recipient ciphertext lives in
     * `message_envelopes`. Legacy plaintext rows stay false.
     */
    val encrypted: Boolean = false,
) {
    /** True when the message is a tombstone; the content is cleared server-side. */
    val deleted: Boolean get() = deletedAt != null
}

/** Result of marking a conversation read: the affected ids and the read timestamp. */
data class ReadMark(val messageIds: List<String>, val readAt: Long)

class Messages(
    private val db: JdbcClient,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
    private val onNewMessage: (MessageRecord) -> Unit = {},
) {

    companion object {
        const val MAX_CONTENT_BYTES = 8192
        const val MAX_PAGE = 200
        /**
         * Upper bound for one envelope string on the wire (ADR-0015):
         * `"<type>:<base64>"` — a 8 KiB plaintext encrypts to well under
         * 12 KiB of base64, so 16 KiB leaves headroom without letting a
         * client stuff arbitrary data into the relay.
         */
        const val MAX_ENVELOPE_CHARS = 16384
        /** Envelope wire shape: ciphertext type (2 = ratchet, 3 = prekey) + base64 payload. */
        val ENVELOPE_PATTERN = Regex("^[23]:[A-Za-z0-9+/]+={0,2}$")

        /** Every persisted column, in [fromRow] order. */
        private const val SELECT_COLUMNS =
            "id, sender, recipient, content, created_at, client_key, read_at, type, media_id, width, height, reply_to, deleted_at, deleted_by, duration_ms, waveform, edited_at, group_id, encrypted"

        /** Validates an envelope map: shape and size of every value (ADR-0015). */
        fun requireValidEnvelopes(envelopes: Map<String, String>) {
            require(envelopes.isNotEmpty()) { "envelopes must not be empty" }
            for ((user, envelope) in envelopes) {
                require(user.isNotBlank()) { "envelope recipient must not be blank" }
                require(envelope.length <= MAX_ENVELOPE_CHARS && ENVELOPE_PATTERN.matches(envelope)) {
                    "malformed envelope for $user"
                }
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
        rs.getLong("edited_at").let { if (rs.wasNull()) null else it },
        rs.getString("group_id"),
        rs.getBoolean("encrypted"),
    )

    /**
     * Stores a pairwise TEXT message. When [envelopes] is given the message
     * is end-to-end encrypted (ADR-0015, Phase 22): the server stores an
     * empty `content`, sets `encrypted`, and keeps one ciphertext per
     * envelope recipient (peer + author self-copy) in `message_envelopes` —
     * the message row and its envelopes commit in one transaction. Without
     * envelopes the legacy plaintext path is kept for pre-E2EE rows only;
     * the API rejects plaintext TEXT sends, so this branch is unreachable
     * from current clients.
     */
    fun insert(
        sender: String,
        recipient: String,
        content: String,
        clientKey: String,
        replyTo: String? = null,
        envelopes: Map<String, String>? = null,
    ): MessageRecord {
        val encrypted = envelopes != null
        if (encrypted) {
            requireValidEnvelopes(envelopes)
        } else {
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(content.isNotEmpty()) { "content must not be empty" }
            require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
        }
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = tx.execute {
            val rows = db.sql(
                "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, reply_to, encrypted) " +
                    "VALUES(:id, :sender, :recipient, :content, :createdAt, :clientKey, :type, :replyTo, :encrypted) ON CONFLICT DO NOTHING",
            ).param("id", id).param("sender", sender).param("recipient", recipient)
                .param("content", if (encrypted) "" else content)
                .param("createdAt", now).param("clientKey", clientKey).param("type", "TEXT").param("replyTo", replyTo)
                .param("encrypted", encrypted)
                .update()
            if (rows > 0 && encrypted) {
                for ((user, envelope) in envelopes) {
                    db.sql(
                        "INSERT INTO message_envelopes(message_id, recipient, envelope) VALUES(:messageId, :recipient, :envelope)",
                    ).param("messageId", id).param("recipient", user).param("envelope", envelope).update()
                }
            }
            rows
        }
        // Read-your-writes lookup runs after the transaction commits.
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
            .param("mediaId", mediaId).param("width", media.width).param("height", media.height)
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
                "WHERE group_id IS NULL AND ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT $columns FROM messages " +
                "WHERE group_id IS NULL AND ((sender = ? AND recipient = ?) OR (sender = ? AND recipient = ?)) " +
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
                "WHERE sender = ? AND recipient = ? AND client_key = ? AND group_id IS NULL",
        ).params(sender, recipient, clientKey).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    /**
     * Finds a group message by the author's client key (Phase 19). The
     * group's idempotency scope is (sender, group, clientKey) — the V14
     * partial unique index binds it.
     */
    fun findInGroup(sender: String, groupId: String, clientKey: String): MessageRecord? =
        db.sql(
            "SELECT $SELECT_COLUMNS FROM messages " +
                "WHERE sender = ? AND group_id = ? AND client_key = ?",
        ).params(sender, groupId, clientKey).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    /**
     * Stores a group text message (Phase 19): one row visible to all
     * members, `recipient` stays '' and `group_id` carries the conversation.
     * The caller must already be validated as a member. With [envelopes]
     * (mandatory from Phase 22, ADR-0015) the row is stored encrypted with
     * one ciphertext per member, committed in the same transaction.
     */
    fun insertGroupText(
        sender: String,
        groupId: String,
        content: String,
        clientKey: String,
        replyTo: String? = null,
        envelopes: Map<String, String>? = null,
    ): MessageRecord {
        val encrypted = envelopes != null
        if (encrypted) {
            requireValidEnvelopes(envelopes)
        } else {
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(content.isNotEmpty()) { "content must not be empty" }
            require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
        }
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = tx.execute {
            val rows = db.sql(
                "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, reply_to, group_id, encrypted) " +
                    "VALUES(:id, :sender, '', :content, :createdAt, :clientKey, 'TEXT', :replyTo, :groupId, :encrypted) ON CONFLICT DO NOTHING",
            ).param("id", id).param("sender", sender)
                .param("content", if (encrypted) "" else content)
                .param("createdAt", now).param("clientKey", clientKey).param("replyTo", replyTo)
                .param("groupId", groupId).param("encrypted", encrypted)
                .update()
            if (rows > 0 && encrypted) {
                for ((user, envelope) in envelopes) {
                    db.sql(
                        "INSERT INTO message_envelopes(message_id, recipient, envelope) VALUES(:messageId, :recipient, :envelope)",
                    ).param("messageId", id).param("recipient", user).param("envelope", envelope).update()
                }
            }
            rows
        }
        val stored = findInGroup(sender, groupId, clientKey)
            ?: error("stored group message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    /**
     * Stores a group IMAGE message (Phase 21): like [insertMedia] but the
     * conversation is the group — `recipient` stays '' and `group_id` carries
     * it. The caller must already be validated as a member and the media row
     * must already be scoped to this group.
     */
    fun insertGroupMedia(sender: String, groupId: String, clientKey: String, mediaId: String, media: MediaRecord, replyTo: String? = null): MessageRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, media_id, width, height, reply_to, group_id) " +
                "VALUES(:id, :sender, '', '', :createdAt, :clientKey, 'IMAGE', :mediaId, :width, :height, :replyTo, :groupId) ON CONFLICT DO NOTHING",
        ).param("id", id).param("sender", sender)
            .param("createdAt", now).param("clientKey", clientKey)
            .param("mediaId", mediaId).param("width", media.width).param("height", media.height)
            .param("replyTo", replyTo).param("groupId", groupId).update()
        val stored = findInGroup(sender, groupId, clientKey)
            ?: error("stored group message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    /**
     * Stores a group VOICE message (Phase 21): the group-scoped twin of
     * [insertVoice] with the recorded duration and waveform.
     */
    fun insertGroupVoice(sender: String, groupId: String, clientKey: String, mediaId: String, durationMs: Int, waveform: String, replyTo: String? = null): MessageRecord {
        val id = newId()
        val now = System.currentTimeMillis()
        val inserted = db.sql(
            "INSERT INTO messages(id, sender, recipient, content, created_at, client_key, type, media_id, duration_ms, waveform, reply_to, group_id) " +
                "VALUES(:id, :sender, '', '', :createdAt, :clientKey, 'VOICE', :mediaId, :durationMs, :waveform, :replyTo, :groupId) ON CONFLICT DO NOTHING",
        ).param("id", id).param("sender", sender)
            .param("createdAt", now).param("clientKey", clientKey)
            .param("mediaId", mediaId).param("durationMs", durationMs).param("waveform", waveform)
            .param("replyTo", replyTo).param("groupId", groupId).update()
        val stored = findInGroup(sender, groupId, clientKey)
            ?: error("stored group message disappeared after insert")
        if (inserted > 0) onNewMessage(stored)
        return stored
    }

    /**
     * Group message history (Phase 19): newest first, cursor semantics
     * identical to the pairwise [page], scoped to one group. Membership is
     * the caller's responsibility (the endpoint checks it before paging).
     */
    fun groupPage(groupId: String, before: Pair<Long, String>?, limit: Int): List<MessageRecord> {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE)
        val columns = SELECT_COLUMNS
        val sql = if (before == null) {
            "SELECT $columns FROM messages WHERE group_id = ? ORDER BY created_at DESC, id DESC LIMIT ?"
        } else {
            "SELECT $columns FROM messages WHERE group_id = ? " +
                "AND (created_at < ? OR (created_at = ? AND id < ?)) " +
                "ORDER BY created_at DESC, id DESC LIMIT ?"
        }
        var statement = db.sql(sql).param(groupId)
        if (before != null) statement = statement.param(before.first).param(before.first).param(before.second)
        return statement.param(effectiveLimit).query { rs, _ -> fromRow(rs) }.list()
    }

    fun findById(id: String): MessageRecord? =
        db.sql(
            "SELECT $SELECT_COLUMNS FROM messages WHERE id = ?",
        ).param(id).query { rs, _ -> fromRow(rs) }.optional().orElse(null)

    /**
     * The ciphertext envelope stored for [user] on message [messageId]
     * (ADR-0015): each participant only ever fetches their own copy. Null
     * for plaintext/media messages or when the user has no envelope.
     */
    fun envelopeFor(messageId: String, user: String): String? =
        db.sql(
            "SELECT envelope FROM message_envelopes WHERE message_id = ? AND recipient = ?",
        ).params(messageId, user).query(String::class.java).optional().orElse(null)

    /**
     * Envelope lookup for a history page: one query for all [messageIds],
     * keyed by message id. Used to personalize DTOs per requesting user.
     */
    fun envelopesFor(messageIds: List<String>, user: String): Map<String, String> {
        if (messageIds.isEmpty()) return emptyMap()
        val values = messageIds.joinToString(",") { "?" }
        return db.sql(
            "SELECT message_id, envelope FROM message_envelopes WHERE recipient = ? AND message_id IN ($values)",
        ).param(user).params(messageIds).query { rs, _ ->
            rs.getString("message_id") to rs.getString("envelope")
        }.list().toMap()
    }

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
     * Per-member read state for group messages (Phase 19): one row per
     * (message, reader) in `group_message_reads`, leaving the pairwise
     * `read_at` column untouched. Marks the group's messages sent by
     * *others* up to [upTo]; the author's own messages are never marked by
     * themselves. Idempotent like the pairwise mark.
     */
    fun markGroupRead(groupId: String, reader: String, upTo: Pair<Long, String>?): ReadMark {
        val now = System.currentTimeMillis()
        val statement = db.sql(
            "WITH target AS (" +
                "SELECT id FROM messages WHERE group_id = ? AND sender <> ? AND deleted_at IS NULL " +
                (if (upTo == null) "" else "AND (created_at < ? OR (created_at = ? AND id <= ?)) ") +
                ") INSERT INTO group_message_reads(message_id, username, read_at) " +
                "SELECT id, ?, ? FROM target ON CONFLICT (message_id, username) DO NOTHING " +
                "RETURNING message_id",
        )
        var bound = statement.param(groupId).param(reader)
        if (upTo != null) bound = bound.param(upTo.first).param(upTo.first).param(upTo.second)
        val ids = bound.param(reader).param(now).query(String::class.java).list().filterNotNull()
        return ReadMark(ids, now)
    }

    /**
     * How many members have read each of the listed messages (Phase 19):
     * the "3 read" counter badge data. Returns a map keyed by message id.
     */
    fun groupReadCounts(messageIds: List<String>): Map<String, Int> {
        if (messageIds.isEmpty()) return emptyMap()
        val values = messageIds.joinToString(",") { "?" }
        return db.sql(
            "SELECT message_id, COUNT(*) AS readers FROM group_message_reads " +
                "WHERE message_id IN ($values) GROUP BY message_id",
        ).params(messageIds).query { rs, _ ->
            rs.getString("message_id") to rs.getInt("readers")
        }.list().toMap()
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
        // The tombstone and the envelope cleanup commit together: a deleted
        // message must not leave ciphertext behind on the relay (ADR-0015).
        tx.execute {
            db.sql(
                "UPDATE messages SET content = '', deleted_at = ?, deleted_by = ? " +
                    "WHERE id = ? AND deleted_at IS NULL",
            ).params(now, deletedBy, messageId).update()
            db.sql("DELETE FROM message_envelopes WHERE message_id = ?").param(messageId).update()
        }
        return findById(messageId)
    }

    /**
     * Re-stores the content of one message (Phase 17). Only the author may
     * edit and only a message still in the conversation: the update matches
     * the exact sender/recipient pair and stamps `edited_at`. Tombstones are
     * rejected by the `deleted_at IS NULL` guard, so deletes take
     * precedence. The read receipt is deliberately preserved (ADR-0010: a
     * receipt means a human looked; an edit never un-reads a message).
     *
     * When [encrypted] is true (ADR-0015, Phase 22) the new ciphertext must
     * arrive in [envelopes] — keyed like on send — and the stored content
     * stays empty; the update and the envelope upserts commit together.
     * Legacy plaintext rows take the plaintext path.
     */
    fun edit(messageId: String, author: String, content: String, encrypted: Boolean, envelopes: Map<String, String>? = null): MessageRecord? {
        val now = System.currentTimeMillis()
        if (encrypted) {
            val env = envelopes ?: emptyMap()
            requireValidEnvelopes(env)
            tx.execute {
                db.sql(
                    "UPDATE messages SET content = '', edited_at = ? " +
                        "WHERE id = ? AND sender = ? AND deleted_at IS NULL",
                ).params(now, messageId, author).update()
                for ((user, envelope) in env) {
                    db.sql(
                        "INSERT INTO message_envelopes(message_id, recipient, envelope) VALUES(?, ?, ?) " +
                            "ON CONFLICT (message_id, recipient) DO UPDATE SET envelope = EXCLUDED.envelope",
                    ).params(messageId, user, envelope).update()
                }
            }
        } else {
            val bytes = content.toByteArray(Charsets.UTF_8)
            require(content.isNotEmpty()) { "content must not be empty" }
            require(bytes.size <= MAX_CONTENT_BYTES) { "content too large" }
            db.sql(
                "UPDATE messages SET content = ?, edited_at = ? " +
                    "WHERE id = ? AND sender = ? AND deleted_at IS NULL",
            ).params(content, now, messageId, author).update()
        }
        return findById(messageId)
    }
}
