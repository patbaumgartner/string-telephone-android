package com.patbaumgartner.android.stringtelephone.voice

import android.annotation.SuppressLint
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One finished voice recording (Phase 11): the encoded audio, the content
 * type it was encoded with, and the metadata the message will carry.
 */
data class VoiceRecording(
    val bytes: ByteArray,
    val contentType: String,
    val fileName: String,
    /** Voice length in milliseconds. */
    val durationMs: Int,
    /** Waveform samples 0-100 as a comma-separated string (server format). */
    val waveform: String,
)

/**
 * Recording state the composer reacts to (Phase 11). `Recording` carries the
 * elapsed time for the live timer and the latest amplitude samples for the
 * live waveform bar. There is no "locked" enum value: a hands-free take is the
 * same recording, and the composer tracks the lock itself to label the bar.
 */
sealed interface VoiceRecordingState {
    data object Idle : VoiceRecordingState
    data class Recording(
        val elapsedMs: Long,
        /** Live waveform bars sampled at [SAMPLE_INTERVAL_MS]; max [MAX_SAMPLES]. */
        val samples: List<Int>,
    ) : VoiceRecordingState
}

/**
 * Records audio with [MediaRecorder]: Opus-in-OGG from API 29, AAC-in-M4A
 * on API 26–28. Samples the mic amplitude at [SAMPLE_INTERVAL_MS] for the
 * waveform, and cleans up its temp file after the caller reads the bytes.
 *
 * Everything Android-specific sits behind this seam so the composer and
 * ViewModel stay unit-testable.
 */
interface VoiceRecorder {
    companion object {
        /** Waveform sample cadence; capped at [MAX_SAMPLES] samples. */
        const val SAMPLE_INTERVAL_MS = 50L
        const val MAX_SAMPLES = 64
        const val MIN_DURATION_MS = 300
        const val MAX_DURATION_MS = 600_000

        /**
         * Whether [MediaRecorder] supports the Opus encoder (API 29+).
         * Below that, we fall back to AAC in M4A (both on the allow-list).
         */
        fun supportsOpus(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    /** Elapsed time, live amplitude samples, and the current gesture state. */
    val state: StateFlow<VoiceRecordingState>

    /**
     * Starts recording into a private temp file. Returns true on success,
     * false on any error (fail-closed: no partial recordings left on disk).
     */
    fun start(): Boolean

    /**
     * Stops and returns the finished recording, or null on failure. The temp
     * file is deleted before the callback fires, and the callback runs
     * synchronously on the calling thread.
     */
    fun finish(onFinished: (VoiceRecording?) -> Unit)

    /**
     * Aborts the current recording without saving; deletes the temp file.
     * Safe to call at any time, including when idle.
     */
    fun cancel()
}

/** Default [VoiceRecorder] implementation over Android [MediaRecorder]. */
class MediaRecorderVoiceRecorder(
    private val context: android.content.Context,
    private val outputDir: File,
    /** Scope for the waveform sampler; cancelled whenever a recording ends. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : VoiceRecorder {

    private val _state = MutableStateFlow<VoiceRecordingState>(VoiceRecordingState.Idle)
    // One read-only wrapper per flow: a getter that called asStateFlow() would
    // hand Compose a new instance on every read and restart every collector.
    private val stateView = _state.asStateFlow()
    override val state: StateFlow<VoiceRecordingState> get() = stateView

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0
    private var samples = mutableListOf<Int>()
    private var samplerJob: Job? = null

    override fun start(): Boolean {
        if ((state.value as? VoiceRecordingState.Recording) != null) return false
        val opus = VoiceRecorder.supportsOpus()
        val file = File(outputDir, "voice-${clock()}.${if (opus) "ogg" else "m4a"}")
        return try {
            recorder = createRecorder().also {
                it.setAudioSource(MediaRecorder.AudioSource.MIC)
                if (opus) {
                    it.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    it.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    it.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    it.setAudioEncodingBitRate(48_000)
                    it.setAudioSamplingRate(44_100)
                    it.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                it.setOutputFile(file.absolutePath)
                it.prepare()
                it.start()
            }
            outputFile = file
            startedAtMs = clock()
            samples.clear()
            _state.value = VoiceRecordingState.Recording(0L, emptyList())
            startSampling()
            true
        } catch (_: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            _state.value = VoiceRecordingState.Idle
            false
        }
    }

    @SuppressLint("RestrictedApi")
    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    override fun finish(onFinished: (VoiceRecording?) -> Unit) {
        samplerJob?.cancel()
        samplerJob = null
        val file = outputFile
        val elapsed = elapsedMs()
        val currentSamples = samples.toList()
        stopRecorderQuietly()
        _state.value = VoiceRecordingState.Idle
        if (file == null || !file.exists() || elapsed < VoiceRecorder.MIN_DURATION_MS) {
            file?.delete()
            onFinished(null)
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        val opus = VoiceRecorder.supportsOpus()
        onFinished(
            bytes?.takeIf { it.isNotEmpty() }?.let {
                VoiceRecording(
                    bytes = it,
                    contentType = if (opus) "audio/ogg" else "audio/mp4",
                    fileName = if (opus) "voice.ogg" else "voice.m4a",
                    durationMs = elapsed.toInt(),
                    waveform = currentSamples.joinToString(","),
                )
            },
        )
    }

    override fun cancel() {
        samplerJob?.cancel()
        samplerJob = null
        stopRecorderQuietly()
        outputFile?.delete()
        outputFile = null
        _state.value = VoiceRecordingState.Idle
    }

    private fun stopRecorderQuietly() {
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
    }

    private fun elapsedMs(): Long =
        if (startedAtMs == 0L) 0 else (clock() - startedAtMs).coerceAtLeast(0)

    /**
     * Samples the mic amplitude while a recording runs. Armed by [start] and
     * cancelled by [finish]/[cancel], so it never ticks while idle and every
     * recording gets its own sampler.
     */
    private fun startSampling() {
        samplerJob?.cancel()
        samplerJob = scope.launch {
            while (isActive) {
                delay(VoiceRecorder.SAMPLE_INTERVAL_MS)
                val current = state.value
                if (current !is VoiceRecordingState.Recording) continue
                val amplitude = recorder?.maxAmplitude ?: 0
                val normalized = ((amplitude / 32767.0) * 100).toInt().coerceIn(0, 100)
                if (samples.size < VoiceRecorder.MAX_SAMPLES) samples.add(normalized)
                _state.value = VoiceRecordingState.Recording(
                    elapsedMs = elapsedMs(),
                    samples = samples.toList(),
                )
            }
        }
    }
}