package com.freespeak.ime.audio

/**
 * Result of a complete user-controlled recording session after VAD silence filtering.
 * Note: Never splits sentences into multiple segments — [samples] is always a single contiguous
 * 16kHz mono float buffer preserving natural clause pauses so the ASR model outputs full punctuation.
 */
data class FilteredAudioResult(
    val samples: FloatArray,
    val hasVoiceActivity: Boolean,
    val rawDurationMs: Long,
    val speechDurationMs: Long,
)

/**
 * Streaming VAD silence filter.
 *
 * Responsibilities:
 * 1. Detect whether meaningful speech occurred during the session ([hasVoiceActivity]).
 * 2. Strip leading & trailing dead air while keeping natural pre-roll (~160ms) and post-roll (~224ms).
 * 3. Filter out multi-second silent gaps (> ~576ms) mid-recording while preserving natural
 *    short breath pauses (150ms–550ms) so punctuated ASR models retain full prosodic cues.
 * 4. NEVER split speech into separate utterances.
 */
class VadSilenceFilter(
    private val detector: SpeechProbabilityDetector,
    private val speechThreshold: Float = 0.45f,
    private val minSpeechFrames: Int = 3,      // ~96ms minimum voiced energy to count as real speech
    private val preRollFrames: Int = 5,        // ~160ms pre-roll before speech onset
    private val maxRetainedSilenceFrames: Int = 14, // ~448ms natural pause kept for punctuation prosody
) {
    private val preRollRing = ArrayDeque<FloatArray>(preRollFrames + 1)
    private val retainedChunks = ArrayList<FloatArray>(256)
    private val trailingSilenceBuffer = ArrayList<FloatArray>(maxRetainedSilenceFrames + 1)

    private var totalFrames = 0
    private var voicedFrames = 0
    private var speechStarted = false
    private var consecutiveSilenceFrames = 0
    private var lastSpeechActive = false

    val isCurrentlySpeech: Boolean get() = lastSpeechActive

    @Synchronized
    fun reset() {
        detector.reset()
        preRollRing.clear()
        retainedChunks.clear()
        trailingSilenceBuffer.clear()
        totalFrames = 0
        voicedFrames = 0
        speechStarted = false
        consecutiveSilenceFrames = 0
        lastSpeechActive = false
    }

    /**
     * Feeds arbitrary-length 16kHz PCM samples (must be chunked into 512-sample frames by caller or [feedSamples]).
     */
    private var remainder = FloatArray(CHUNK_SIZE)
    private var remainderCount = 0

    @Synchronized
    fun feedSamples(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset): Boolean {
        var idx = offset
        val end = offset + length
        while (idx < end) {
            val toCopy = minOf(CHUNK_SIZE - remainderCount, end - idx)
            System.arraycopy(samples, idx, remainder, remainderCount, toCopy)
            remainderCount += toCopy
            idx += toCopy

            if (remainderCount == CHUNK_SIZE) {
                processFrame512(remainder.copyOf())
                remainderCount = 0
            }
        }
        return lastSpeechActive
    }

    private fun processFrame512(frame: FloatArray) {
        totalFrames++
        val prob = detector.detectProbability(frame)
        val isSpeech = prob >= speechThreshold
        lastSpeechActive = isSpeech

        if (isSpeech) {
            voicedFrames++
            if (!speechStarted) {
                speechStarted = true
                // Flush pre-roll ring so initial consonants are preserved
                while (preRollRing.isNotEmpty()) {
                    retainedChunks.add(preRollRing.removeFirst())
                }
            } else if (trailingSilenceBuffer.isNotEmpty()) {
                // Speech resumed: flush up to maxRetainedSilenceFrames (~448ms) of natural pause
                retainedChunks.addAll(trailingSilenceBuffer)
                trailingSilenceBuffer.clear()
            }
            consecutiveSilenceFrames = 0
            retainedChunks.add(frame)
        } else {
            if (!speechStarted) {
                if (preRollRing.size >= preRollFrames) {
                    preRollRing.removeFirst()
                }
                preRollRing.addLast(frame)
            } else {
                consecutiveSilenceFrames++
                if (trailingSilenceBuffer.size < maxRetainedSilenceFrames) {
                    trailingSilenceBuffer.add(frame)
                }
                // Any silent frames beyond maxRetainedSilenceFrames are filtered out!
            }
        }
    }

    @Synchronized
    fun finish(bypassFilter: Boolean = false): FilteredAudioResult {
        if (remainderCount > 0) {
            val padded = FloatArray(CHUNK_SIZE)
            System.arraycopy(remainder, 0, padded, 0, remainderCount)
            processFrame512(padded)
            remainderCount = 0
        }

        val rawDurationMs = (totalFrames.toLong() * CHUNK_SIZE * 1000L) / SAMPLE_RATE
        val hasValidSpeech = voicedFrames >= minSpeechFrames

        if (!hasValidSpeech && !bypassFilter) {
            return FilteredAudioResult(
                samples = FloatArray(0),
                hasVoiceActivity = false,
                rawDurationMs = rawDurationMs,
                speechDurationMs = 0L,
            )
        }

        // Append a gentle ~192ms (6 frames) post-roll from trailing silence so final syllables aren't clipped
        val postRollCount = minOf(6, trailingSilenceBuffer.size)
        for (i in 0 until postRollCount) {
            retainedChunks.add(trailingSilenceBuffer[i])
        }
        trailingSilenceBuffer.clear()

        val totalSamples = retainedChunks.size * CHUNK_SIZE
        val output = FloatArray(totalSamples)
        var pos = 0
        for (chunk in retainedChunks) {
            System.arraycopy(chunk, 0, output, pos, chunk.size)
            pos += chunk.size
        }

        val speechDurationMs = (output.size.toLong() * 1000L) / SAMPLE_RATE
        return FilteredAudioResult(
            samples = output,
            hasVoiceActivity = output.isNotEmpty(),
            rawDurationMs = rawDurationMs,
            speechDurationMs = speechDurationMs,
        )
    }

    companion object {
        const val CHUNK_SIZE = 512
        const val SAMPLE_RATE = 16000
    }
}
