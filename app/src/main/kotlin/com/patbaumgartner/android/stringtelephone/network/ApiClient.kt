package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ContactsDto
import com.patbaumgartner.android.stringtelephone.core.MarkReadRequest
import com.patbaumgartner.android.stringtelephone.core.MarkReadResponseDto
import com.patbaumgartner.android.stringtelephone.core.MeDto
import com.patbaumgartner.android.stringtelephone.core.MessageDto
import com.patbaumgartner.android.stringtelephone.core.MessagesPageDto
import com.patbaumgartner.android.stringtelephone.core.SendMessageRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Typed failure kinds so the UI can render precise, honest error states. */
sealed class ApiException(message: String) : Exception(message) {
    object Auth : ApiException("authentication failed")
    object Forbidden : ApiException("forbidden")
    object Server : ApiException("server error")
    object Network : ApiException("network error")
    data class Unexpected(val status: Int) : ApiException("unexpected status")
    data class Protocol(val detail: String) : ApiException("protocol error")
}

data class MeResult(val username: String, val displayName: String)

data class MessageResult(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
    /** Epoch millisecond at which the recipient read the message; null while unread (ADR-0010). */
    val readAt: Long? = null,
    val type: String = "TEXT",
    val mediaId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Reactions on this message (ADR-0011, Phase 8). */
    val reactions: List<com.patbaumgartner.android.stringtelephone.core.ReactionDto> = emptyList(),
    /** Message this one replies to; null when not a reply (ADR-0011, Phase 9). */
    val replyToId: String? = null,
    /** Tombstone (ADR-0011, Phase 10): non-null once deleted for both sides. */
    val deletedAt: Long? = null,
    /** Who deleted the message; null while it exists. */
    val deletedBy: String? = null,
)

data class MessagesPage(val messages: List<MessageResult>, val nextCursor: String?)

class CompanionApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String,
    client: OkHttpClient? = null,
) {
    init {
        requireAllowedServerUrl(baseUrl)
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    private val http: OkHttpClient = (client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()).withTransportSecurity()

    private inline fun <T> request(block: () -> T): T = try {
        block()
    } catch (_: IOException) {
        throw ApiException.Network
    } catch (_: SerializationException) {
        throw ApiException.Protocol("invalid response")
    }

    /** Base URL of the companion API (for building media URLs). */
    fun baseUrl(): String = baseUrl

    /** Current bearer token (for authenticated media fetches). */
    fun authToken(): String = tokenProvider()

    fun me(): MeResult = request {
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/me")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .get()
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> {
                    val me = json.decodeFromString(MeDto.serializer(), r.body.string())
                    return@request MeResult(me.username, me.displayName)
                }
            }
        }
    }

    fun contacts(): List<ContactDto> = request {
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/contacts")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .get()
                .build(),
        ).execute()
        response.use { r ->
            if (r.code == 401 || r.code == 403) throw ApiException.Auth
            if (!r.isSuccessful) throw ApiException.Unexpected(r.code)
            json.decodeFromString(ContactsDto.serializer(), r.body.string()).contacts
        }
    }

    fun sendMessage(recipient: String, content: String, clientKey: String, replyToId: String? = null): MessageResult = sendText(recipient, content, clientKey, replyToId)
    fun sendText(recipient: String, content: String, clientKey: String, replyToId: String? = null): MessageResult = request {
        val payload = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(recipient, content, clientKey, replyToId = replyToId),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$recipient/messages")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .post(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            when (r.code) {
                401, 403 -> throw ApiException.Auth
                404 -> throw ApiException.Protocol("peer unknown")
                413 -> throw ApiException.Protocol("content too large")
                in 200..299 -> { val m = json.decodeFromString(MessageDto.serializer(), r.body.string()); return@request MessageResult(m.id, m.sender, m.recipient, m.content, m.createdAt, m.clientKey, m.readAt, m.type, m.mediaId, m.width, m.height, m.reactions ?: emptyList(), m.replyToId) }
                else -> throw ApiException.Unexpected(r.code)
            }
        }
    }

    fun sendMedia(recipient: String, mediaId: String, clientKey: String, width: Int? = null, height: Int? = null, replyToId: String? = null): MessageResult = request {
        val payload = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(recipient, "", clientKey, "IMAGE", mediaId, replyToId),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$recipient/messages")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .post(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            when (r.code) {
                401, 403 -> throw ApiException.Auth
                404 -> throw ApiException.Protocol("peer unknown")
                in 200..299 -> { val m = json.decodeFromString(MessageDto.serializer(), r.body.string()); return@request MessageResult(m.id, m.sender, m.recipient, m.content, m.createdAt, m.clientKey, m.readAt, m.type, m.mediaId, m.width, m.height, m.reactions ?: emptyList(), m.replyToId) }
                else -> throw ApiException.Unexpected(r.code)
            }
        }
    }

    /**
     * Uploads a media blob (image) to the companion API. Returns the server
     * record, whose [MediaRecord.id] is referenced when sending the message.
     */
    fun uploadMedia(peer: String, bytes: ByteArray, contentType: String, fileName: String): com.patbaumgartner.android.stringtelephone.core.MediaRecordDto = request {
        val boundary = "----StringTelephone" + System.currentTimeMillis()
        val body = okhttp3.MultipartBody.Builder(boundary)
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("peer", peer)
            .addFormDataPart("file", fileName, bytes.toRequestBody(contentType.toMediaType()))
            .build()
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/media")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .post(body)
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 413 -> throw ApiException.Protocol("media too large")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> json.decodeFromString(com.patbaumgartner.android.stringtelephone.core.MediaRecordDto.serializer(), r.body.string())
            }
        }
    }

    fun messages(peer: String, cursor: String?, limit: Int = 50): MessagesPage = request {
        val url = buildString {
            append("$baseUrl/v1/conversations/$peer/messages?limit=$limit")
            if (cursor != null) append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        }
        val response = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${tokenProvider()}").get().build()).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("unknown peer")
                r.code == 400 -> throw ApiException.Protocol("invalid cursor")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> {
                    val page = json.decodeFromString(MessagesPageDto.serializer(), r.body.string())
                    return@request MessagesPage(
                        messages = page.messages.map {
                            MessageResult(
                                it.id, it.sender, it.recipient, it.content, it.createdAt, it.clientKey,
                                it.readAt, it.type, it.mediaId, it.width, it.height,
                                it.reactions.orEmpty(), it.replyToId, it.deletedAt, it.deletedBy,
                            )
                        },
                        nextCursor = page.nextCursor,
                    )
                }
            }
        }
    }

    /**
     * Adds or replaces the caller's reaction on a message (ADR-0011, Phase 8).
     * The response carries the full reaction list for the message after the
     * change, so the caller can update the chips without a history refresh.
     */
    fun setReaction(peer: String, messageId: String, emoji: String): List<com.patbaumgartner.android.stringtelephone.core.ReactionDto> = request {
        val payload = json.encodeToString(
            com.patbaumgartner.android.stringtelephone.core.SetReactionRequest.serializer(),
            com.patbaumgartner.android.stringtelephone.core.SetReactionRequest(emoji),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/messages/$messageId/reactions")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .put(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("no such message")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> decodeReactions(r.body.string())
            }
        }
    }

    /** Removes the caller's reaction; idempotent (ADR-0011, Phase 8). */
    fun removeReaction(peer: String, messageId: String): List<com.patbaumgartner.android.stringtelephone.core.ReactionDto> = request {
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/messages/$messageId/reactions")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .delete()
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("no such message")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> decodeReactions(r.body.string())
            }
        }
    }

    /**
     * Deletes a message for both sides (ADR-0011, Phase 10): the server
     * tombstones it and clears the content. The response carries the
     * tombstone so the caller can render the placeholder immediately.
     */
    fun deleteMessage(peer: String, messageId: String): MessageResult = request {
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/messages/$messageId")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .delete()
                .build(),
        ).execute()
        response.use { r ->
            val body = r.body.string()
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("no such message")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> json.decodeFromString(MessageDto.serializer(), body).let { m ->
                    MessageResult(m.id, m.sender, m.recipient, m.content, m.createdAt, m.clientKey, m.readAt, m.type, m.mediaId, m.width, m.height, m.reactions ?: emptyList(), m.replyToId, m.deletedAt, m.deletedBy)
                }
            }
        }
    }

    private fun decodeReactions(body: String): List<com.patbaumgartner.android.stringtelephone.core.ReactionDto> =
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.patbaumgartner.android.stringtelephone.core.ReactionDto.serializer()),
            body,
        )

    /**
     * Marks the peer's messages as read (ADR-0010). The caller must be the
     * foreground chat screen: a notification, a background sync or a cursor
     * catch-up must never send this.
     */
    fun markRead(peer: String, upToMessageId: String? = null): MarkReadResponseDto = request {
        val payload = json.encodeToString(
            MarkReadRequest.serializer(),
            MarkReadRequest(upToMessageId),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/read")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .post(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("unknown peer")
                r.code == 400 -> throw ApiException.Protocol("invalid read mark")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> json.decodeFromString(MarkReadResponseDto.serializer(), r.body.string())
            }
        }
    }
}
