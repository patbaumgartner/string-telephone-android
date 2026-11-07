package com.patbaumgartner.stringtelephone

import com.patbaumgartner.stringtelephone.media.MediaObject
import com.patbaumgartner.stringtelephone.media.MediaStore
import com.patbaumgartner.stringtelephone.media.mediaKey
import org.springframework.jdbc.core.simple.JdbcClient
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

data class MediaRecord(
    val id: String,
    val owner: String,
    /** Pairwise conversation peer; null for group media (Phase 21, V16). */
    val peer: String?,
    /** Group the media belongs to; null for pairwise media (Phase 21, V16). */
    val groupId: String? = null,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val state: String,
    val thumbnailKey: String?,
    val createdAt: Long,
    val width: Int? = null,
    val height: Int? = null,
)

class MediaService(
    private val db: JdbcClient,
    private val store: MediaStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Group membership check (Phase 21): does [username] belong to [groupId]. */
    private val groupMember: (groupId: String, username: String) -> Boolean = { _, _ -> false },
) {
    companion object {
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val MAX_AUDIO_BYTES = 50L * 1024 * 1024
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        val AUDIO_TYPES = setOf("audio/mpeg", "audio/ogg", "audio/mp4", "audio/aac", "audio/wav")
    }

    fun allowed(contentType: String, size: Long): Boolean {
        val t = contentType.lowercase()
        if (t in IMAGE_TYPES) return size <= MAX_IMAGE_BYTES
        if (t in AUDIO_TYPES) return size <= MAX_AUDIO_BYTES
        return false
    }

    /**
     * Stores original bytes; for images generates a size-capped JPEG thumbnail
     * (ADR-0009). Ownership is recorded server-side (never client-supplied).
     * The media belongs to exactly one conversation: a pairwise [peer] or a
     * [groupId] (Phase 21, V16) — the caller has already checked membership.
     */
    fun upload(owner: String, peer: String?, contentType: String, bytes: ByteArray, groupId: String? = null): MediaRecord {
        require((peer == null) != (groupId == null)) { "exactly one of peer or groupId is required" }
        if (!allowed(contentType, bytes.size.toLong())) {
            throw ApiFailure(org.springframework.http.HttpStatus.BAD_REQUEST, "unsupported or too large media")
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val sha = sha256Hex(bytes)
        val key = mediaKey(id, "original")
        store.put(key, contentType, bytes.size.toLong(), ByteArrayInputStream(bytes))

        var thumbKey: String? = null
        var dimensions: Pair<Int, Int>? = null
        if (contentType.lowercase() in IMAGE_TYPES) {
            thumbKey = generateThumbnail(id, bytes)
            dimensions = imageDimensions(bytes)
        }

        val now = clock()
        db.sql(
            "INSERT INTO media(id,owner,peer,group_id,content_type,byte_size,sha256,state,thumbnail_key,created_at,width,height) VALUES(:id,:owner,:peer,:groupId,:contentType,:byteSize,:sha256,:state,:thumbnailKey,:createdAt,:width,:height)",
        ).param("id", id).param("owner", owner).param("peer", peer).param("groupId", groupId)
            .param("contentType", contentType).param("byteSize", bytes.size.toLong()).param("sha256", sha)
            .param("state", "ready").param("thumbnailKey", thumbKey ?: "").param("createdAt", now)
            .param("width", dimensions?.first ?: 0).param("height", dimensions?.second ?: 0)
            .update()
        return MediaRecord(id, owner, peer, groupId, contentType, bytes.size.toLong(), sha, "ready", thumbKey, now, dimensions?.first, dimensions?.second)
    }

    /**
     * Only the conversation's participants may fetch media: the owner and peer
     * for pairwise media, every group member for group media (Phase 21 —
     * membership is the authorisation, exactly like the group REST paths).
     */
    private fun authorize(record: MediaRecord, requester: String) {
        val groupId = record.groupId
        val allowed = if (groupId != null) {
            requester == record.owner || groupMember(groupId, requester)
        } else {
            requester == record.owner || requester == record.peer
        }
        if (!allowed) throw ApiFailure(org.springframework.http.HttpStatus.FORBIDDEN, "forbidden")
    }

    fun authorizeAndGet(id: String, requester: String): MediaObject {
        val record = mediaRecord(id) ?: throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "not found")
        authorize(record, requester)
        val key = mediaKey(id, "original")
        return store.get(key)
    }

    /** Streams the server-generated thumbnail (falls back to the original for non-images). */
    fun authorizeAndGetThumbnail(id: String, requester: String): MediaObject {
        val record = mediaRecord(id) ?: throw ApiFailure(org.springframework.http.HttpStatus.NOT_FOUND, "not found")
        authorize(record, requester)
        val key = if (record.thumbnailKey.isNullOrBlank() || record.thumbnailKey == "") {
            mediaKey(id, "original")
        } else {
            mediaKey(id, "thumb")
        }
        return store.get(key)
    }

    private fun mediaRecord(id: String): MediaRecord? = db.sql(
        "SELECT id,owner,peer,group_id,content_type,byte_size,sha256,state,thumbnail_key,created_at,width,height FROM media WHERE id=?",
    ).params(id).query { rs, _ ->
        MediaRecord(
            rs.getString("id"), rs.getString("owner"), rs.getString("peer"), rs.getString("group_id"),
            rs.getString("content_type"), rs.getLong("byte_size"), rs.getString("sha256"),
            rs.getString("state"), rs.getString("thumbnail_key"), rs.getLong("created_at"),
            rs.getObject("width", Integer::class.java)?.toInt()?.takeIf { it > 0 },
            rs.getObject("height", Integer::class.java)?.toInt()?.takeIf { it > 0 },
        )
    }.optional().orElse(null)

    fun getRecord(id: String): MediaRecord? = mediaRecord(id)

    private fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return image.width to image.height
    }

    private fun generateThumbnail(id: String, bytes: ByteArray): String? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val maxDim = 256
        val scale = minOf(maxDim.toDouble() / image.width.toDouble(), maxDim.toDouble() / image.height.toDouble(), 1.0)
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g2 = scaled.createGraphics()
        g2.drawImage(image.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null)
        g2.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "jpg", out)
        val thumbBytes = out.toByteArray()
        val key = mediaKey(id, "thumb")
        store.put(key, "image/jpeg", thumbBytes.size.toLong(), ByteArrayInputStream(thumbBytes))
        return key
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
