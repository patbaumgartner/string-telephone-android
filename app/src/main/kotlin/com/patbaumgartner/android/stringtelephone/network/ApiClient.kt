package com.patbaumgartner.android.stringtelephone.network

import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.ContactsDto
import com.patbaumgartner.android.stringtelephone.core.ConversationStateDto
import com.patbaumgartner.android.stringtelephone.core.MarkReadRequest
import com.patbaumgartner.android.stringtelephone.core.MarkReadResponseDto
import com.patbaumgartner.android.stringtelephone.core.MeDto
import com.patbaumgartner.android.stringtelephone.core.MessageDto
import com.patbaumgartner.android.stringtelephone.core.MessagesPageDto
import com.patbaumgartner.android.stringtelephone.core.SendMessageRequest
import com.patbaumgartner.android.stringtelephone.core.UpdateConversationStateRequest
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
    /** Voice message duration in milliseconds (Phase 11); null for non-voice messages. */
    val durationMs: Int? = null,
    /** Comma-separated waveform amplitudes 0-100 (Phase 11); null for non-voice messages. */
    val waveform: String? = null,
    /** Epoch ms at which the author last edited the message (Phase 17); null when never edited. */
    val editedAt: Long? = null,
)

data class MessagesPage(val messages: List<MessageResult>, val nextCursor: String?)

/** Maps a wire message onto the app-side result, keeping every field in sync in one place. */
private fun toMessageResult(m: MessageDto) = MessageResult(
    m.id, m.sender, m.recipient, m.content, m.createdAt, m.clientKey, m.readAt, m.type, m.mediaId,
    m.width, m.height, m.reactions ?: emptyList(), m.replyToId, m.deletedAt, m.deletedBy, m.durationMs, m.waveform, m.editedAt,
)

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

    /**
     * Reads the signed-in user's pin/mute/archive state for one conversation
     * (Phase 18); absent state comes back all-false.
     */
    fun conversationState(peer: String): ConversationStateDto = request {
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/state")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .get()
                .build(),
        ).execute()
        response.use { r ->
            if (r.code == 401 || r.code == 403) throw ApiException.Auth
            if (r.code == 404) throw ApiException.Protocol("no such user")
            if (!r.isSuccessful) throw ApiException.Unexpected(r.code)
            json.decodeFromString(ConversationStateDto.serializer(), r.body.string())
        }
    }

    /**
     * Updates any subset of pin/mute/archive for one conversation (Phase 18);
     * the response is the full state after the change.
     */
    fun updateConversationState(peer: String, pinned: Boolean?, muted: Boolean?, archived: Boolean?): ConversationStateDto = request {
        val payload = json.encodeToString(
            UpdateConversationStateRequest.serializer(),
            UpdateConversationStateRequest(pinned, muted, archived),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/state")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .put(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            if (r.code == 401 || r.code == 403) throw ApiException.Auth
            if (r.code == 404) throw ApiException.Protocol("no such user")
            if (r.code == 400) throw ApiException.Protocol("state update rejected")
            if (!r.isSuccessful) throw ApiException.Unexpected(r.code)
            json.decodeFromString(ConversationStateDto.serializer(), r.body.string())
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
                in 200..299 -> { val m = json.decodeFromString(MessageDto.serializer(), r.body.string()); return@request toMessageResult(m) }
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
                in 200..299 -> { val m = json.decodeFromString(MessageDto.serializer(), r.body.string()); return@request toMessageResult(m) }
                else -> throw ApiException.Unexpected(r.code)
            }
        }
    }

    /**
     * Sends a VOICE message (Phase 11): the uploaded audio media plus the
     * recorded duration and waveform, so the receiver renders the player
     * without decoding the file first.
     */
    fun sendVoice(recipient: String, mediaId: String, clientKey: String, durationMs: Int, waveform: String, replyToId: String? = null): MessageResult = request {
        val payload = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(recipient, "", clientKey, "VOICE", mediaId, replyToId, durationMs, waveform),
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
                in 200..299 -> { val m = json.decodeFromString(MessageDto.serializer(), r.body.string()); return@request toMessageResult(m) }
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
                        messages = page.messages.map { toMessageResult(it) },
                        nextCursor = page.nextCursor,
                    )
                }
            }
        }
    }

    /**
     * Full-text search within one conversation (Phase 16). Same page shape
     * as [messages]; the server scopes to the authenticated pair, matches
     * case-insensitively (simple tsvector config) and paginates oldest-first
     * pages going back in time via [cursor].
     */
    fun search(peer: String, query: String, cursor: String?, limit: Int = 50): MessagesPage = request {
        val url = buildString {
            append("$baseUrl/v1/conversations/$peer/search?limit=$limit&q=")
                .append(java.net.URLEncoder.encode(query, "UTF-8"))
            if (cursor != null) append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        }
        val response = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${tokenProvider()}").get().build()).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("unknown peer")
                r.code == 400 -> throw ApiException.Protocol("invalid query or cursor")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> {
                    val page = json.decodeFromString(MessagesPageDto.serializer(), r.body.string())
                    return@request MessagesPage(
                        messages = page.messages.map { toMessageResult(it) },
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
                    toMessageResult(m)
                }
            }
        }
    }

    /**
     * Re-stores the content of one message (Phase 17): PUT with the new
     * text. The server enforces author-only, no time window, and 409 for
     * tombstones; the response is the updated record.
     */
    fun editMessage(peer: String, messageId: String, content: String): MessageResult = request {
        val payload = json.encodeToString(
            com.patbaumgartner.android.stringtelephone.core.EditMessageRequest.serializer(),
            com.patbaumgartner.android.stringtelephone.core.EditMessageRequest(content),
        )
        val response = http.newCall(
            Request.Builder()
                .url("$baseUrl/v1/conversations/$peer/messages/$messageId")
                .header("Authorization", "Bearer ${tokenProvider()}")
                .put(payload.toRequestBody(jsonMedia))
                .build(),
        ).execute()
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw ApiException.Auth
                r.code == 404 -> throw ApiException.Protocol("no such message")
                r.code == 409 -> throw ApiException.Protocol("message cannot be edited")
                !r.isSuccessful -> throw ApiException.Unexpected(r.code)
                else -> json.decodeFromString(MessageDto.serializer(), r.body.string()).let { m ->
                    toMessageResult(m)
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
