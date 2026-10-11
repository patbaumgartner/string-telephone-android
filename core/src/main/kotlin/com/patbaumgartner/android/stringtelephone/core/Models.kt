package com.patbaumgartner.android.stringtelephone.core

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val username: String,
    val displayName: String,
    val active: Boolean,
)

@Serializable
data class Contact(
    val id: String,
    val username: String,
    val displayName: String,
    val presence: PresenceStatus,
    val presenceUpdatedAtEpochMs: Long?,
)

enum class PresenceStatus { ONLINE, OFFLINE, UNKNOWN }

data class PresenceState(
    val value: PresenceStatus,
    val observedAtEpochMs: Long?,
    val ttlMs: Long,
) {
    fun effective(nowEpochMs: Long): PresenceStatus = when {
        value == PresenceStatus.ONLINE && observedAtEpochMs != null &&
            nowEpochMs - observedAtEpochMs <= ttlMs -> PresenceStatus.ONLINE
        value == PresenceStatus.OFFLINE -> PresenceStatus.OFFLINE
        else -> PresenceStatus.UNKNOWN
    }
}

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val content: String,
    val sentAtEpochMs: Long,
    val clientKey: String,
    val status: MessageStatus,
)

enum class MessageStatus { PENDING, SENT, DELIVERED, FAILED }

data class AccountIdentity(
    val username: String,
    val displayName: String,
)

enum class RegistrationState { UNREGISTERED, REGISTERING, REGISTERED, FAILED }

enum class CallState {
    IDLE, REGISTERING, READY, OUTGOING, RINGING, CONNECTING, ACTIVE, ENDING, FAILED
}
