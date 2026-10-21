package com.patbaumgartner.stringtelephone.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class InMemoryMediaStore : MediaStore {
    data class Stored(
        val contentType: String,
        val contentLength: Long,
        val bytes: ByteArray,
    )

    private val objects = ConcurrentHashMap<String, Stored>()

    override fun put(key: String, contentType: String, contentLength: Long, body: InputStream) {
        val bytes = body.readBytes()
        objects[key] = Stored(contentType, contentLength, bytes)
    }

    override fun get(key: String): MediaObject {
        val stored = objects[key] ?: throw java.io.FileNotFoundException("media not found: $key")
        return MediaObject(
            contentType = stored.contentType,
            contentLength = stored.contentLength,
            body = ByteArrayInputStream(stored.bytes),
        )
    }

    override fun delete(key: String) {
        objects.remove(key)
    }
}
