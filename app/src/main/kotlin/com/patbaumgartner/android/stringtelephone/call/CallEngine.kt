package com.patbaumgartner.android.stringtelephone.call

import com.patbaumgartner.android.stringtelephone.core.CallState
import kotlinx.coroutines.flow.StateFlow

data class CallSessionInfo(
    val remoteUser: String,
    val state: CallState,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val startedAtEpochMs: Long? = null,
)

sealed interface CallEvent {
    data object Registered : CallEvent
    data object RegistrationFailed : CallEvent
    data class IncomingCall(val remoteUser: String) : CallEvent
    data class OutgoingRinging(val remoteUser: String) : CallEvent
    data class CallConnected(val remoteUser: String) : CallEvent
    data object CallEnded : CallEvent
    data class CallFailed(val reason: String, val failure: CallFailure = CallFailure.OTHER) : CallEvent
}

/** Why a call could not be set up, in terms the call screen can explain. */
enum class CallFailure { NOT_CONNECTED, BUSY, DECLINED, UNAVAILABLE, NO_ANSWER, OTHER }

/**
 * Maps a SIP final response (and liblinphone's reason name) to a [CallFailure].
 * Asterisk answers 480 both when the callee is offline and when it rang out,
 * so [peerRang] (a 180 Ringing arrived) tells the two apart.
 */
internal fun callFailureFor(protocolCode: Int, reason: String, peerRang: Boolean): CallFailure = when {
    protocolCode == 486 || protocolCode == 600 || reason == "Busy" -> CallFailure.BUSY
    protocolCode == 603 || reason == "Declined" || reason == "DoNotDisturb" -> CallFailure.DECLINED
    protocolCode in setOf(401, 403, 407) ||
        reason in setOf("Unauthorized", "Forbidden", "IOError", "NoResponse", "ServerTimeout") -> CallFailure.NOT_CONNECTED
    peerRang || reason == "NotAnswered" -> CallFailure.NO_ANSWER
    protocolCode in setOf(404, 408, 410, 480, 503) ||
        reason in setOf("NotFound", "Gone", "TemporarilyUnavailable") -> CallFailure.UNAVAILABLE
    else -> CallFailure.OTHER
}

/**
 * SIP engine boundary. Linphone lives behind this interface so domain and UI
 * code never depends on the SDK and can be tested against fakes.
 */
interface CallEngine {
    val state: StateFlow<CallSessionInfo>
    val events: kotlinx.coroutines.flow.SharedFlow<CallEvent>

    fun start(config: EngineConfig)
    fun stop()
    fun call(remoteUser: String)
    fun accept()
    fun reject()
    fun hangUp()
    fun setMute(muted: Boolean)
    fun setSpeaker(on: Boolean)
}

data class EngineConfig(
    val serverHost: String,
    val serverPort: Int,
    val username: String,
    val sipSecret: String,
    val transport: Transport = Transport.TLS,
) {
    enum class Transport { UDP, TCP, TLS }

    internal fun requireAllowedTransport(development: Boolean) {
        require(development || transport == Transport.TLS) { "Production calls require SIP TLS" }
    }

    internal fun registrarUri(): String {
        val host = if (serverHost.contains(':')) "[$serverHost]" else serverHost
        return "sip:$host:$serverPort;transport=${transport.name.lowercase()}"
    }

    internal fun callUri(remoteUser: String): String {
        require(remoteUser.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid SIP username" }
        return registrarUri().replaceFirst("sip:", "sip:$remoteUser@")
    }
}
