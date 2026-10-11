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
    data class CallFailed(val reason: String) : CallEvent
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
    val transport: Transport = Transport.UDP,
) {
    enum class Transport { UDP, TCP, TLS }
}
