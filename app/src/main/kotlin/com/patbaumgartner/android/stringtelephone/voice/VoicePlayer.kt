package com.patbaumgartner.android.stringtelephone.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback state the voice bubble reacts to (Phase 11).
 *
 * [playbackPosition] is updated by the polling loop; the UI exposes it as
 * the slider position so the user can scrub without fighting the poller.
 */
sealed interface VoicePlayerState {
    /**
     * Media the state belongs to, or null when nothing is loaded. The chat
     * shows several voice bubbles but owns a single player, so each bubble
     * matches this against its own media id instead of assuming that any
     * active state is its own.
     */
    val mediaId: String?

    data object Idle : VoicePlayerState {
        override val mediaId: String? = null
    }

    data class Playing(
        override val mediaId: String,
        val durationMs: Int,
        val playbackPosition: Int,   // milliseconds, updated by the poller
    ) : VoicePlayerState

    data class Paused(
        override val mediaId: String,
        val durationMs: Int,
        val position: Int,
    ) : VoicePlayerState
}

/** Available playback speed multipliers (Phase 11). */
enum class PlaybackSpeed(val value: Float, val label: String) {
    X05(0.5f, "0.5×"),
    X075(0.75f, "0.75×"),
    X1(1.0f, "1×"),
    X15(1.5f, "1.5×"),
    X2(2.0f, "2×"),
}

/**
 * Plays voice messages from the server's media endpoint with speed control
 * and pause/resume. All state flows from the poller — the UI just reads it.
 */
interface VoicePlayer {
    companion object {
        const val POLL_INTERVAL_MS = 100L
    }

    val state: StateFlow<VoicePlayerState>
    val currentSpeed: StateFlow<PlaybackSpeed>

    /**
     * Starts playing [mediaUrl] with the caller's bearer [token] as a request
     * header (the media endpoint authenticates the same way as the REST API).
     * Any previously playing track is replaced. [mediaId] identifies the
     * message this playback belongs to, so the UI can show controls on the
     * right bubble.
     */
    fun play(mediaId: String, mediaUrl: String, token: String?): Boolean

    fun pause()
    fun resume()
    fun seekTo(positionMs: Int)
    fun cycleSpeed()
    fun stop()
}

/** Default [VoicePlayer] over Android [MediaPlayer]. */
class MediaPlayerVoicePlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) : VoicePlayer {

    private val _state = MutableStateFlow<VoicePlayerState>(VoicePlayerState.Idle)
    // One read-only wrapper per flow: a getter that called asStateFlow() would
    // hand Compose a new instance on every read and restart every collector.
    private val stateView = _state.asStateFlow()
    override val state: StateFlow<VoicePlayerState> get() = stateView
    private val _currentSpeed = MutableStateFlow(PlaybackSpeed.X1)
    private val currentSpeedView = _currentSpeed.asStateFlow()
    override val currentSpeed: StateFlow<PlaybackSpeed> get() = currentSpeedView

    private var player: MediaPlayer? = null
    private var pollerJob: Job? = null

    /** Media id of the loaded track; null when nothing is loaded. */
    private var loadedMediaId: String? = null

    override fun play(mediaId: String, mediaUrl: String, token: String?): Boolean {
        stopQuietly()
        return try {
            player = MediaPlayer().apply {
                setDataSource(context, mediaUrl.toUri(), mapOf("Authorization" to "Bearer ${token.orEmpty()}"))
                prepareAsync()
                setOnPreparedListener { mp ->
                    setSpeed(mp, _currentSpeed.value.value)
                    loadedMediaId = mediaId
                    mp.start()
                    startPoller(mp, mediaId)
                    _state.value = VoicePlayerState.Playing(mediaId, mp.duration, 0)
                }
                setOnErrorListener { _, _, _ ->
                    stopQuietly()
                    true
                }
            }
            true
        } catch (_: Exception) {
            stopQuietly()
            false
        }
    }

    private fun setSpeed(mp: MediaPlayer, speed: Float) {
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (_: Exception) {
            // Speed control unsupported on this API level (pre-23).
        }
    }

    override fun pause() {
        val mp = player ?: return
        val mediaId = loadedMediaId ?: return
        pollerJob?.cancel()
        pollerJob = null
        runCatching { mp.pause() }
        _state.value = VoicePlayerState.Paused(mediaId, mp.duration, mp.currentPosition)
    }

    override fun resume() {
        val mp = player ?: return
        val mediaId = loadedMediaId ?: return
        runCatching { mp.start() }
        startPoller(mp, mediaId)
        _state.value = VoicePlayerState.Playing(mediaId, mp.duration, mp.currentPosition)
    }

    override fun seekTo(positionMs: Int) {
        val mp = player ?: return
        runCatching { mp.seekTo(positionMs) }
        val current = state.value
        _state.value = if (current is VoicePlayerState.Playing) {
            current.copy(playbackPosition = positionMs)
        } else if (current is VoicePlayerState.Paused) {
            current.copy(position = positionMs)
        } else {
            current
        }
    }

    override fun cycleSpeed() {
        val speeds = PlaybackSpeed.entries
        _currentSpeed.value = speeds[(speeds.indexOf(_currentSpeed.value) + 1) % speeds.size]
        val mp = player ?: return
        setSpeed(mp, _currentSpeed.value.value)
    }

    override fun stop() = stopQuietly()

    private fun startPoller(mp: MediaPlayer, mediaId: String) {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            while (isActive) {
                delay(VoicePlayer.POLL_INTERVAL_MS)
                if (mp.isPlaying) {
                    val pos = mp.currentPosition
                    _state.value = VoicePlayerState.Playing(mediaId, mp.duration, pos)
                    if (pos >= mp.duration - VoicePlayer.POLL_INTERVAL_MS) {
                        _state.value = VoicePlayerState.Idle
                        stopQuietly()
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopQuietly() {
        pollerJob?.cancel()
        pollerJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        loadedMediaId = null
        _state.value = VoicePlayerState.Idle
    }
}