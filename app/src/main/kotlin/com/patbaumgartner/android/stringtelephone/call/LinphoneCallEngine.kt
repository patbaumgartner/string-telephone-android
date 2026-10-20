package com.patbaumgartner.android.stringtelephone.call

import android.content.Context
import com.patbaumgartner.android.stringtelephone.BuildConfig
import com.patbaumgartner.android.stringtelephone.core.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.RegistrationState
import java.util.concurrent.atomic.AtomicReference

/**
 * Linphone-backed CallEngine. Callbacks arrive on liblinphone's iterate
 * thread, so the audio-device listeners hop onto the engine's main-threaded
 * scope before steering devices or updating state, keeping them serialised
 * with UI calls such as [setAudioRoute]; state writes use atomic updates so
 * call-state transitions published from the iterate thread are never
 * clobbered. Domain code observes StateFlows.
 */
class LinphoneCallEngine(
    private val context: Context,
) : CallEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(
        CallSessionInfo(remoteUser = "", state = CallState.IDLE),
    )
    override val state: StateFlow<CallSessionInfo> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CallEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    private val coreRef = AtomicReference<Core?>(null)
    private var config: EngineConfig? = null

    private val listener = object : CoreListenerStub() {
        override fun onRegistrationStateChanged(core: Core, proxyCfg: org.linphone.core.ProxyConfig, state: RegistrationState, message: String) {
            when (state) {
                RegistrationState.Ok -> {
                    _events.tryEmit(CallEvent.Registered)
                    if (_state.value.state == CallState.REGISTERING || _state.value.state == CallState.FAILED) {
                        _state.value = _state.value.copy(state = CallState.READY)
                    }
                }
                RegistrationState.Failed -> {
                    _events.tryEmit(CallEvent.RegistrationFailed)
                    _state.value = _state.value.copy(state = CallState.FAILED)
                }
                else -> Unit
            }
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            val tracked = activeCall
            if (state == Call.State.IncomingReceived) {
                if (tracked != null && !sameCall(tracked, call)) {
                    // One call at a time: a second caller hears busy instead of replacing this call.
                    call.decline(org.linphone.core.Reason.Busy)
                    return
                }
                activeCall = call
                peerRang = false
                val remote = call.remoteAddress.username ?: ""
                _state.value = CallSessionInfo(remoteUser = remote, state = CallState.RINGING)
                publishAudioState(core)
                _events.tryEmit(CallEvent.IncomingCall(remote))
                return
            }
            if (state == Call.State.OutgoingInit && tracked == null) {
                // Fires inside inviteWithParams, before it returns the call.
                activeCall = call
                peerRang = false
                return
            }
            // Late events from an earlier call (e.g. its Released) must not end the current one.
            if (tracked == null || !sameCall(tracked, call)) return
            when (state) {
                Call.State.OutgoingProgress, Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    if (state != Call.State.OutgoingProgress) peerRang = true
                    if (_state.value.state != CallState.OUTGOING) {
                        val remote = call.remoteAddress.username ?: ""
                        _state.value = _state.value.copy(remoteUser = remote, state = CallState.OUTGOING)
                        _events.tryEmit(CallEvent.OutgoingRinging(remote))
                    }
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    if (_state.value.state != CallState.ACTIVE) {
                        // Re-apply the route chosen while the call connected;
                        // if liblinphone picks its own device anyway,
                        // publishAudioState below mirrors what really happens.
                        outputDeviceFor(core, _state.value.audioRoute)
                            ?.let { core.setOutputAudioDevice(it) }
                        _state.value = _state.value.copy(
                            state = CallState.ACTIVE,
                            startedAtEpochMs = _state.value.startedAtEpochMs ?: System.currentTimeMillis(),
                        )
                        publishAudioState(core)
                        _events.tryEmit(CallEvent.CallConnected(_state.value.remoteUser))
                    }
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    activeCall = null
                    userPinnedRoute = null
                    core.micGainDb = 0f
                    _state.value = _state.value.copy(state = CallState.IDLE, startedAtEpochMs = null, muted = false)
                    if (state == Call.State.Error) {
                        val error = call.errorInfo
                        val failure = callFailureFor(error.protocolCode, error.reason.name, peerRang)
                        _events.tryEmit(CallEvent.CallFailed(message, failure))
                    } else {
                        _events.tryEmit(CallEvent.CallEnded)
                    }
                }
                else -> Unit
            }
        }

        override fun onAudioDevicesListUpdated(core: Core) {
            // Iterate-thread callback: hop onto the engine scope so device
            // steering and the read-modify-write below serialise with
            // setAudioRoute from the UI thread.
            scope.launch {
                val available = availableRoutes(core)
                val callActive = activeCall != null
                val previous = _state.value
                val resolved = resolveAudioRoute(
                    previous.availableAudioRoutes,
                    previous.audioRoute,
                    available,
                    userPinnedRoute,
                )
                if (resolved != previous.audioRoute && callActive) {
                    // Mid-call steering only; an idle-time choice is re-applied
                    // on the call-connected transition.
                    outputDeviceFor(core, resolved)?.let { core.setOutputAudioDevice(it) }
                }
                // The pinned route really disconnected: forget the pin so the
                // next headset connection may take over again.
                if (userPinnedRoute?.let { it !in available } == true) userPinnedRoute = null
                _state.update { it.copy(audioRoute = resolved, availableAudioRoutes = available) }
            }
        }

        override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) {
            // Mirrors routing liblinphone chose itself (system policy,
            // Bluetooth takeover). Wired/USB outputs map to no route; see
            // toAudioRoute().
            val route = audioDevice.type.toAudioRoute() ?: return
            scope.launch {
                val available = availableRoutes(core)
                _state.update { current ->
                    current.copy(audioRoute = route, availableAudioRoutes = available)
                }
            }
        }
    }

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var peerRang = false

    /** The route the user tapped explicitly; null until they choose one. */
    @Volatile
    private var userPinnedRoute: AudioRoute? = null

    private fun sameCall(a: Call, b: Call): Boolean = a === b || a.callLog.callId == b.callLog.callId

    override fun start(config: EngineConfig) {
        config.requireAllowedTransport(development = BuildConfig.DEBUG)
        this.config = config
        val factory = Factory.instance()
        factory.setDebugMode(false, "")
        val core = factory.createCore("", "", context)
        core.addListener(listener)
        configureCallSecurity(core, config)
        // Device ringtone and vibration, honouring silent and Do Not Disturb modes.
        core.isNativeRingingEnabled = true
        core.isKeepAliveEnabled = true
        check(core.start() == 0) { "SIP engine could not start" }
        coreRef.set(core)

        val accountParams = core.createAccountParams().also { params ->
            params.identityAddress = core.createAddress("sip:${config.username}@${config.serverHost}")
            params.serverAddress = core.createAddress(config.registrarUri())
            params.setRegisterEnabled(true)
        }
        val account = core.createAccount(accountParams)
        core.addAccount(account)
        core.setDefaultAccount(account)
        // Install the SIP secret, not the separate companion API token.
        core.addAuthInfo(factory.createAuthInfo(config.username, null, config.sipSecret, null, null, null))
        _state.value = _state.value.copy(
            state = CallState.REGISTERING,
            availableAudioRoutes = availableRoutes(core),
        )
    }

    override fun stop() {
        activeCall = null
        userPinnedRoute = null
        coreRef.getAndSet(null)?.let { core ->
            // Drop stored digest credentials so a signed-out core holds no secret.
            core.clearAllAuthInfo()
            core.stop()
        }
        _state.value = CallSessionInfo(remoteUser = "", state = CallState.IDLE)
    }

    override fun call(remoteUser: String) {
        if (activeCall != null) return
        val core = coreRef.get()
        val currentConfig = config
        if (core == null || currentConfig == null) {
            failToStart(remoteUser, "SIP engine not started", CallFailure.NOT_CONNECTED)
            return
        }
        val params = core.createCallParams(null)
        if (params == null) {
            failToStart(remoteUser, "No call parameters", CallFailure.OTHER)
            return
        }
        params.account = core.defaultAccount
        _state.value = _state.value.copy(remoteUser = remoteUser, state = CallState.OUTGOING)
        val call = core.inviteWithParams(currentConfig.callUri(remoteUser), params)
        if (call == null) {
            activeCall = null
            failToStart(remoteUser, "INVITE could not be sent", CallFailure.NOT_CONNECTED)
            return
        }
        val ended = call.state in setOf(Call.State.End, Call.State.Error, Call.State.Released)
        if (activeCall == null && !ended && _state.value.state == CallState.OUTGOING) {
            peerRang = false
            activeCall = call
        }
    }

    /** Reports a call that never reached the network so the UI can explain it. */
    private fun failToStart(remoteUser: String, reason: String, failure: CallFailure) {
        _state.value = _state.value.copy(remoteUser = remoteUser, state = CallState.IDLE)
        _events.tryEmit(CallEvent.CallFailed(reason, failure))
    }

    override fun accept() {
        activeCall?.accept()
    }

    override fun reject() {
        activeCall?.decline(org.linphone.core.Reason.Busy)
    }

    override fun hangUp() {
        val call = activeCall ?: return
        if (call.state == Call.State.IncomingReceived) {
            call.decline(org.linphone.core.Reason.Busy)
        } else {
            call.terminate()
        }
    }

    override fun setMute(muted: Boolean) {
        val core = coreRef.get() ?: return
        core.micGainDb = if (muted) -60f else 0f
        core.currentCall?.microphoneMuted = muted
        _state.value = _state.value.copy(muted = muted)
    }

    override fun setAudioRoute(route: AudioRoute) {
        val core = coreRef.get() ?: return
        val device = outputDeviceFor(core, route) ?: return
        core.setOutputAudioDevice(device)
        // Remember the explicit choice so a transient Bluetooth flap cannot
        // take the audio back (resolveAudioRoute).
        userPinnedRoute = route
        _state.update { it.copy(audioRoute = route) }
    }

    /** Republishes route availability and the device liblinphone actually picked. */
    private fun publishAudioState(core: Core) {
        val selected = core.outputAudioDevice?.type?.toAudioRoute()
        val available = availableRoutes(core)
        _state.update { it.copy(audioRoute = selected ?: it.audioRoute, availableAudioRoutes = available) }
    }

    /** The routes liblinphone can currently play audio to. */
    private fun availableRoutes(core: Core): Set<AudioRoute> = core.audioDevices
        .filter { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
        .mapNotNull { it.type.toAudioRoute() }
        .toSet()

    /** The device to hand liblinphone for [route]; HFP beats A2DP for a live call. */
    private fun outputDeviceFor(core: Core, route: AudioRoute): AudioDevice? {
        val outputs = core.audioDevices.filter {
            it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) && it.type in route.deviceTypes()
        }
        return outputs.firstOrNull { it.type == AudioDevice.Type.Bluetooth } ?: outputs.firstOrNull()
    }
}

/** Output-device types in liblinphone that serve each of our routes. */
private fun AudioRoute.deviceTypes(): Set<AudioDevice.Type> = when (this) {
    AudioRoute.EARPIECE -> setOf(AudioDevice.Type.Earpiece)
    AudioRoute.SPEAKER -> setOf(AudioDevice.Type.Speaker)
    AudioRoute.BLUETOOTH -> setOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.BluetoothA2DP)
}

/**
 * Maps a liblinphone device type onto our route vocabulary. Wired outputs
 * (Headset, Headphones, HearingAid, GenericUsb) intentionally map to null:
 * they are out of the Phase 2 vocabulary, so when one takes over, the buttons
 * keep showing the fallback route while audio plays on the wired device.
 * Recorded in docs/feature-plan.md as a known limitation.
 */
private fun AudioDevice.Type.toAudioRoute(): AudioRoute? = when (this) {
    AudioDevice.Type.Earpiece -> AudioRoute.EARPIECE
    AudioDevice.Type.Speaker -> AudioRoute.SPEAKER
    AudioDevice.Type.Bluetooth, AudioDevice.Type.BluetoothA2DP -> AudioRoute.BLUETOOTH
    else -> null
}

/** SDES carries its SRTP keys inside SIP, so it is only safe over verified TLS. */
internal fun configureCallSecurity(core: Core, config: EngineConfig) {
    if (config.transport != EngineConfig.Transport.TLS) return
    core.verifyServerCertificates(true)
    core.verifyServerCn(true)
    check(core.setMediaEncryption(MediaEncryption.SRTP) == 0) { "SRTP is unavailable" }
    core.setMediaEncryptionMandatory(true)
    core.setSrtpCryptoSuites("AES_CM_128_HMAC_SHA1_80")
    core.setCapabilityNegociationEnabled(false)
    val transports = core.transports
    transports.udpPort = 0
    transports.tcpPort = 0
    transports.dtlsPort = 0
    transports.tlsPort = -1
    check(core.setTransports(transports) == 0) { "SIP TLS is unavailable" }
}
