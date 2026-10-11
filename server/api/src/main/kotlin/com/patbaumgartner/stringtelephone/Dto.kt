package com.patbaumgartner.stringtelephone

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(val username: String, val displayName: String)

@Serializable
data class ContactDto(
    val username: String,
    val displayName: String,
    val presence: String,
    val presenceUpdatedAtEpochMs: Long?,
)

@kotlinx.serialization.Serializable
data class ContactsDto(val contacts: List<ContactDto>)

@kotlinx.serialization.Serializable
data class MessageDto(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)

@kotlinx.serialization.Serializable
data class SendMessageRequest(val recipient: String, val content: String, val clientKey: String)

@kotlinx.serialization.Serializable
data class MessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)

@kotlinx.serialization.Serializable
data class CreateUserRequest(val username: String, val displayName: String)

@kotlinx.serialization.Serializable
data class CreateResponse(val username: String, val displayName: String, val apiToken: String)

@kotlinx.serialization.Serializable
data class RevokeRequest(val username: String)

@kotlinx.serialization.Serializable
data class RevokeResponse(val username: String, val revoked: Boolean)

@kotlinx.serialization.Serializable
data class UserListDto(val users: List<UserDto>)

@kotlinx.serialization.Serializable
data class UserDto(val username: String, val displayName: String, val active: Boolean)

@kotlinx.serialization.Serializable
data class ErrorDto(val error: String)

@kotlinx.serialization.Serializable
data class WsMessageDto(val type: String, val payload: String)

/** WebSocket event frames pushed on /v1/ws. */
@kotlinx.serialization.Serializable
data class WsNewMessageEvent(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)
