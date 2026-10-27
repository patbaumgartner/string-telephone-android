package com.patbaumgartner.android.stringtelephone.call

import com.patbaumgartner.android.stringtelephone.core.CallState
import kotlinx.coroutines.flow.StateFlow

/** Where call audio plays. [BLUETOOTH] covers HFP and A2DP headsets. */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH }

/** Which way the camera points. [FRONT] is the selfie camera. */
enum class CameraFacing { FRONT, BACK }

data class CallSessionInfo(
    val remoteUser: String,
    val state: CallState,
    val muted: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.EARPIECE,
    val availableAudioRoutes: Set<AudioRoute> = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER),
    val startedAtEpochMs: Long? = null,
    /** The peer offered video or the user asked to add it to this call. */
    val videoOffered: Boolean = false,
    /** The camera is currently allowed to send; false while only receiving. */
    val videoEnabled: Boolean = false,
    /** A video stream is negotiated and running in either direction. */
    val videoActive: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.FRONT,
)

/**
 * Video starts only when the user asked for it and the camera permission is
 * granted. A denied permission never blocks the audio call.
 */
internal fun startWithVideo(requested: Boolean, cameraAllowed: Boolean): Boolean = requested && cameraAllowed

/**
 * Leaving the call screen always removes the video stream and leaves audio
 * running: Android 11+ hands backgrounded apps silence instead of camera
 * frames, and a hidden camera is never acceptable. Pure so the background
 * rule is unit-tested rather than inferred from the Activity lifecycle.
 */
internal fun afterCallScreenHidden(info: CallSessionInfo): CallSessionInfo =
    if (!info.videoOffered && !info.videoEnabled && !info.videoActive) info
    else info.copy(videoOffered = false, videoEnabled = false, videoActive = false)

/** Cycles through the cameras liblinphone reports, wrapping at the end. */
internal fun nextCameraFacing(current: CameraFacing, available: List<CameraFacing>): CameraFacing {
    if (available.isEmpty()) return current
    val index = available.indexOf(current)
    return if (index < 0) available.first() else available[(index + 1) % available.size]
}

/**
 * Video calls start on speaker so the peer is audible without holding the
 * phone; audio calls keep the earpiece unless a headset is connected.
 */
internal fun defaultAudioRoute(available: Set<AudioRoute>, withVideo: Boolean): AudioRoute = when {
    AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
    withVideo && AudioRoute.SPEAKER in available -> AudioRoute.SPEAKER
    AudioRoute.EARPIECE in available -> AudioRoute.EARPIECE
    else -> available.firstOrNull() ?: AudioRoute.EARPIECE
}

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
    data class IncomingCall(val remoteUser: String, val withVideo: Boolean = false) : CallEvent
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
    fun call(remoteUser: String, withVideo: Boolean = false)
    fun accept(withVideo: Boolean = false)
    fun reject()
    fun hangUp()
    fun setMute(muted: Boolean)
    fun setAudioRoute(route: AudioRoute)

    /** Turns the camera on (bidirectional) or off (receive-only). */
    fun setVideoEnabled(enabled: Boolean)

    /** Removes the video stream entirely; audio keeps running (background policy). */
    fun releaseVideo()

    /** Switches front/back camera while video is running. */
    fun switchCamera()

    /** Native remote and self-view surfaces (SurfaceView/TextureView); null detaches. */
    fun attachVideoSurfaces(remote: Any?, preview: Any?)
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
