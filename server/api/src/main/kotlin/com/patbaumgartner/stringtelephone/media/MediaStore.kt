package com.patbaumgartner.stringtelephone.media

import java.io.InputStream

/**
 * Provider-neutral seam for object storage (ADR-0009). Implementations must
 * stream blobs without buffering entire objects in memory where possible.
 */
interface MediaStore {
    /** Uploads the stream and returns the object key used to retrieve it. */
    fun put(key: String, contentType: String, contentLength: Long, body: InputStream)

    /** Opens a stream for [key]; the caller must close it. */
    fun get(key: String): MediaObject

    /** Deletes the object if present. */
    fun delete(key: String)
}

data class MediaObject(
    val contentType: String,
    val contentLength: Long,
    val body: InputStream,
)

/** Deterministic object key for a media item (never derived from user input alone in paths outside this seam). */
fun mediaKey(id: String, variant: String): String = "media/$id/$variant"
