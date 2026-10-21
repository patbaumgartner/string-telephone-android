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
    val peer: String,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val state: String,
    val thumbnailKey: String?,
    val createdAt: Long,
)

class MediaService(
    private val db: JdbcClient,
    private val store: MediaStore,
    private val clock: () -> Long = System::currentTimeMillis,
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
     */
    fun upload(owner: String, peer: String, contentType: String, bytes: ByteArray): MediaRecord {
        if (!allowed(contentType, bytes.size.toLong())) {
            throw ApiFailure(org.springframework.http.HttpStatus.BAD_REQUEST, "unsupported or too large media")
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val sha = sha256Hex(bytes)
        val key = mediaKey(id, "original")
        store.put(key, contentType, bytes.size.toLong(), ByteArrayInputStream(bytes))

        var thumbKey: String? = null
        if (contentType.lowercase() in IMAGE_TYPES) {
            thumbKey = generateThumbnail(id, bytes)
        }

        val now = clock()
        db.sql(
            "INSERT INTO media(id,owner,peer,content_type,byte_size,sha256,state,thumbnail_key,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        ).params(id, owner, peer, contentType, bytes.size.toLong(), sha, "ready", thumbKey ?: "", now)
            .update()
        return MediaRecord(id, owner, peer, contentType, bytes.size.toLong(), sha, "ready", thumbKey, now)
    }

    /** Only the owner or peer may fetch media for the conversation. */
    fun authorizeAndGet(id: String, requester: String): MediaObject {
        val record = db.sql(
            "SELECT id,owner,peer,content_type,byte_size,sha256,state,thumbnail_key,created_at FROM media WHERE id=?",
        ).params(id)
            .query { rs, _ ->
                MediaRecord(
                    rs.getString("id"),
                    rs.getString("owner"),
                    rs.getString("peer"),
                    rs.getString("content_type"),
                    rs.getLong("byte_size"),
                    rs.getString("sha256"),
                    rs.getString("state"),
                    rs.getString("thumbnail_key"),
                    rs.getLong("created_at"),
                )
            }.single()
        if (requester != record.owner && requester != record.peer) {
            throw ApiFailure(org.springframework.http.HttpStatus.FORBIDDEN, "forbidden")
        }
        val key = mediaKey(id, "original")
        return store.get(key)
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
