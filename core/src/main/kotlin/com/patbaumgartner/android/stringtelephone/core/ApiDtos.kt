package com.patbaumgartner.android.stringtelephone.core

import kotlinx.serialization.Serializable

@Serializable
data class MeDto(val username: String, val displayName: String)

@Serializable
data class ContactDto(
    val username: String,
    val displayName: String,
    val presence: String,
    val presenceUpdatedAtEpochMs: Long? = null,
)

@Serializable
data class ContactsDto(val contacts: List<ContactDto>)

@Serializable
data class MessageDto(
    val id: String,
    val sender: String,
    val recipient: String,
    val content: String,
    val createdAt: Long,
    val clientKey: String,
)

@Serializable
data class SendMessageRequest(val recipient: String, val content: String, val clientKey: String)

@Serializable
data class MessagesPageDto(val messages: List<MessageDto>, val nextCursor: String?)
