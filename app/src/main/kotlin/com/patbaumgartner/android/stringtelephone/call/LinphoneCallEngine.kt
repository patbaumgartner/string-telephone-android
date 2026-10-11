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
 * Linphone-backed CallEngine. All SDK objects are touched only from the
 * engine's own single-threaded scope; domain code observes StateFlows.
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
                        _state.value = _state.value.copy(
                            state = CallState.ACTIVE,
                            startedAtEpochMs = _state.value.startedAtEpochMs ?: System.currentTimeMillis(),
                        )
                        _events.tryEmit(CallEvent.CallConnected(_state.value.remoteUser))
                    }
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    activeCall = null
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
    }

    private var activeCall: Call? = null
    private var peerRang = false

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
        _state.value = _state.value.copy(state = CallState.REGISTERING)
    }

    override fun stop() {
        activeCall = null
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

    override fun setSpeaker(on: Boolean) {
        val core = coreRef.get() ?: return
        val device = core.audioDevices.firstOrNull {
            if (on) it.type == AudioDevice.Type.Speaker else it.type == AudioDevice.Type.Earpiece
        }
        if (device != null) {
            core.setOutputAudioDevice(device)
            _state.value = _state.value.copy(speakerOn = on)
        }
    }
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
