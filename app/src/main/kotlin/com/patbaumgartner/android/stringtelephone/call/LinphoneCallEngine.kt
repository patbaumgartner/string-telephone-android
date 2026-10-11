package com.patbaumgartner.android.stringtelephone.call

import android.content.Context
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
                    if (_state.value.state == CallState.REGISTERING) {
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
            when (state) {
                Call.State.IncomingReceived -> {
                    val remote = call.remoteAddress.username ?: ""
                    _state.value = CallSessionInfo(remoteUser = remote, state = CallState.RINGING)
                    _events.tryEmit(CallEvent.IncomingCall(remote))
                }
                Call.State.OutgoingProgress -> {
                    val remote = call.remoteAddress.username ?: ""
                    _state.value = _state.value.copy(remoteUser = remote, state = CallState.OUTGOING)
                    _events.tryEmit(CallEvent.OutgoingRinging(remote))
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    _state.value = _state.value.copy(
                        state = CallState.ACTIVE,
                        startedAtEpochMs = _state.value.startedAtEpochMs ?: System.currentTimeMillis(),
                    )
                    _events.tryEmit(CallEvent.CallConnected(_state.value.remoteUser))
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    val failed = state == Call.State.Error
                    _state.value = _state.value.copy(state = if (failed) CallState.FAILED else CallState.IDLE, startedAtEpochMs = null)
                    _events.tryEmit(if (failed) CallEvent.CallFailed(message) else CallEvent.CallEnded)
                }
                else -> Unit
            }
        }
    }

    override fun start(config: EngineConfig) {
        this.config = config
        val factory = Factory.instance()
        factory.setDebugMode(false, "")
        val core = factory.createCore("", "", context)
        core.addListener(listener)
        coreRef.set(core)
        core.start()

        val accountParams = core.createAccountParams().also { params ->
            params.identityAddress = core.createAddress("sip:${config.username}@${config.serverHost}")
            params.serverAddress = core.createAddress("sip:${config.serverHost}:${config.serverPort};transport=${transportToken(config.transport)}")
            params.setRegisterEnabled(true)
        }
        val account = core.createAccount(accountParams)
        core.addAccount(account)
        core.setDefaultAccount(account)
        _state.value = _state.value.copy(state = CallState.REGISTERING)
    }

    override fun stop() {
        coreRef.getAndSet(null)?.stop()
        _state.value = CallSessionInfo(remoteUser = "", state = CallState.IDLE)
    }

    override fun call(remoteUser: String) {
        val core = coreRef.get() ?: return
        core.invite("sip:$remoteUser@${config?.serverHost}")
        _state.value = _state.value.copy(remoteUser = remoteUser, state = CallState.OUTGOING)
    }

    override fun accept() {
        coreRef.get()?.currentCall?.accept()
    }

    override fun reject() {
        coreRef.get()?.currentCall?.decline(org.linphone.core.Reason.Busy)
    }

    override fun hangUp() {
        val call = coreRef.get()?.currentCall ?: return
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

    private fun transportToken(t: EngineConfig.Transport): String = when (t) {
        EngineConfig.Transport.UDP -> "udp"
        EngineConfig.Transport.TCP -> "tcp"
        EngineConfig.Transport.TLS -> "tls"
    }
}
