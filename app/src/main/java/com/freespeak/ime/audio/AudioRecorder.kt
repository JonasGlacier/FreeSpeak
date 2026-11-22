package com.freespeak.ime.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.freespeak.engine.dsp.AudioDspNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Captures 16kHz 16-bit mono PCM audio on a dedicated IO coroutine, feeds real-time RMS levels
 * for the IME waveform visualizer, and passes audio through [VadSilenceFilter] (filtering silent
 * dead air without splitting sentences).
 */
class AudioRecorder(context: Context) {

    private val appContext = context.applicationContext
    private val vadDetector: SpeechProbabilityDetector by lazy {
        SileroOnnxVadDetector.createFromAssetsOrFallback(appContext)
    }

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var activeFilter: VadSilenceFilter? = null
    private var rawBackupChunks: MutableList<FloatArray>? = null
    private var vadEnabledSnapshot: Boolean = true

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        vadEnabled: Boolean = true,
        vadThreshold: Float = 0.45f,
    ): Result<Unit> {
        if (_isRecording.value) return Result.success(Unit)

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            return Result.failure(IllegalStateException("16kHz mono audio capture unsupported ($minBufBytes)"))
        }

        val bufferSizeInBytes = maxOf(minBufBytes * 2, READ_SAMPLES * 2 * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        } catch (e: Exception) {
            return Result.failure(e)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return Result.failure(IllegalStateException("Microphone initialization failed"))
        }

        val filter = VadSilenceFilter(
            detector = vadDetector,
            speechThreshold = vadThreshold,
        ).also { it.reset() }

        vadEnabledSnapshot = vadEnabled
        activeFilter = filter
        rawBackupChunks = if (!vadEnabled) ArrayList(256) else null
        audioRecord = recorder

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            recorder.release()
            audioRecord = null
            return Result.failure(e)
        }

        _isRecording.value = true
        _rmsLevel.value = 0f
        _isSpeechDetected.value = false

        val nativeDspAvailable = AudioDspNative.ensureLoaded()

        captureJob = scope.launch(Dispatchers.IO) {
            val shortBuf = ShortArray(READ_SAMPLES)
            val floatBuf = FloatArray(READ_SAMPLES)

            while (isActive && _isRecording.value) {
                val readCount = recorder.read(shortBuf, 0, shortBuf.size)
                if (readCount <= 0) continue

                for (i in 0 until readCount) {
                    floatBuf[i] = shortBuf[i] / 32768.0f
                }

                val rms = if (nativeDspAvailable) {
                    AudioDspNative.computeNormalizedRms(floatBuf, readCount)
                } else {
                    computeFallbackRms(floatBuf, readCount)
                }
                _rmsLevel.value = rms

                if (vadEnabledSnapshot) {
                    val voiced = filter.feedSamples(floatBuf, 0, readCount)
                    _isSpeechDetected.value = voiced
                } else {
                    rawBackupChunks?.add(floatBuf.copyOfRange(0, readCount))
                    _isSpeechDetected.value = rms > 0.08f
                }
            }
        }

        return Result.success(Unit)
    }

    suspend fun stop(): FilteredAudioResult {
        _isRecording.value = false
        captureJob?.cancelAndJoin()
        captureJob = null

        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        audioRecord = null
        _rmsLevel.value = 0f
        _isSpeechDetected.value = false

        if (!vadEnabledSnapshot) {
            val chunks = rawBackupChunks.orEmpty()
            rawBackupChunks = null
            val total = chunks.sumOf { it.size }
            val merged = FloatArray(total)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, merged, pos, c.size)
                pos += c.size
            }
            val durationMs = (total.toLong() * 1000L) / SAMPLE_RATE
            return FilteredAudioResult(
                samples = merged,
                hasVoiceActivity = total > SAMPLE_RATE / 6,
                rawDurationMs = durationMs,
                speechDurationMs = durationMs,
            )
        }

        val filter = activeFilter
        activeFilter = null
        return filter?.finish() ?: FilteredAudioResult(FloatArray(0), false, 0L, 0L)
    }

    suspend fun cancel() {
        _isRecording.value = false
        captureJob?.cancelAndJoin()
        captureJob = null
        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        audioRecord = null
        activeFilter = null
        rawBackupChunks = null
        _rmsLevel.value = 0f
        _isSpeechDetected.value = false
    }

    fun release() {
        runCatching { vadDetector.close() }
    }

    private fun computeFallbackRms(buf: FloatArray, len: Int): Float {
        if (len <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until len) {
            sumSq += buf[i] * buf[i]
        }
        val rms = sqrt(sumSq / len).toFloat()
        if (rms <= 1e-5f) return 0f
        val db = 20f * log10(rms)
        return ((db + 45f) / 40f).coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val READ_SAMPLES = 512 // 32ms @ 16kHz -> ~31 FPS smooth waveform + exact Silero VAD frame
    }
}
