package com.patbaumgartner.android.stringtelephone.call

import com.patbaumgartner.android.stringtelephone.core.CallState
import kotlinx.coroutines.flow.StateFlow

/** Where call audio plays. [BLUETOOTH] covers HFP and A2DP headsets. */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH }

data class CallSessionInfo(
    val remoteUser: String,
    val state: CallState,
    val muted: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val availableAudioRoutes: Set<AudioRoute> = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val startedAtEpochMs: Long? = null,
)

/**
 * Decides the route after the output-device list changed: fall back when the
 * selected route disappeared, take over when a headset connects mid-call,
 * and otherwise keep the user's choice. Framework-free so unit tests cover it.
 *
 * [userPinnedRoute] is the route the user last tapped explicitly (null when
 * they never chose). A headset reconnecting after a transient device-list
 * flap must not steal the audio back from an explicit non-Bluetooth choice.
 */
internal fun resolveAudioRoute(
    previousAvailable: Set<AudioRoute>,
    current: AudioRoute,
    available: Set<AudioRoute>,
    userPinnedRoute: AudioRoute? = null,
): AudioRoute = when {
    current !in available && AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
    // Speaker before Bluetooth keeps the fallback independent of the order
    // liblinphone happened to enumerate its devices in.
    current !in available ->
        listOf(AudioRoute.SPEAKER, AudioRoute.BLUETOOTH).firstOrNull { it in available } ?: current
    AudioRoute.BLUETOOTH in available &&
        AudioRoute.BLUETOOTH !in previousAvailable &&
        userPinnedRoute != AudioRoute.SPEAKER &&
        userPinnedRoute != AudioRoute.EARPIECE -> AudioRoute.BLUETOOTH
    else -> current
}

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
    fun setAudioRoute(route: AudioRoute)
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
