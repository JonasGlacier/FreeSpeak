package com.freespeak.ime.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.sqrt

interface SpeechProbabilityDetector : AutoCloseable {
    fun reset()
    /**
     * Evaluates speech probability in [0.0f, 1.0f] for a 512-sample (32ms @ 16kHz) mono chunk.
     */
    fun detectProbability(chunk512: FloatArray): Float
    override fun close() {}
}

/**
 * Official Silero VAD v5 ONNX wrapper (16kHz, 64-sample context + 512-sample window = [1, 576]).
 */
class SileroOnnxVadDetector(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : SpeechProbabilityDetector {

    private val contextBuf = FloatArray(CONTEXT_SAMPLES)
    private val inputBuf = FloatArray(CONTEXT_SAMPLES + WINDOW_SAMPLES)
    private var stateFlat = FloatArray(STATE_SIZE)

    override fun reset() {
        contextBuf.fill(0f)
        inputBuf.fill(0f)
        stateFlat.fill(0f)
    }

    override fun detectProbability(chunk512: FloatArray): Float {
        System.arraycopy(contextBuf, 0, inputBuf, 0, CONTEXT_SAMPLES)
        val copyLen = minOf(chunk512.size, WINDOW_SAMPLES)
        System.arraycopy(chunk512, 0, inputBuf, CONTEXT_SAMPLES, copyLen)
        if (copyLen < WINDOW_SAMPLES) {
            inputBuf.fill(0f, CONTEXT_SAMPLES + copyLen, CONTEXT_SAMPLES + WINDOW_SAMPLES)
        }

        // Update rolling 64-sample context from tail of combined input
        System.arraycopy(
            inputBuf,
            inputBuf.size - CONTEXT_SAMPLES,
            contextBuf,
            0,
            CONTEXT_SAMPLES
        )

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuf),
            longArrayOf(1L, (CONTEXT_SAMPLES + WINDOW_SAMPLES).toLong())
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(stateFlat),
            longArrayOf(2L, 1L, 128L)
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE)),
            longArrayOf()
        )

        return try {
            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor,
            )
            session.run(inputs).use { result ->
                val probTensor = result[0] as OnnxTensor
                val nextStateTensor = result[1] as OnnxTensor
                val prob = probTensor.floatBuffer.get(0)

                val nextBuf = nextStateTensor.floatBuffer
                nextBuf.rewind()
                nextBuf.get(stateFlat)
                prob.coerceIn(0f, 1f)
            }
        } finally {
            inputTensor.close()
            stateTensor.close()
            srTensor.close()
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val WINDOW_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val STATE_SIZE = 2 * 1 * 128
        private const val SAMPLE_RATE = 16000L
        private const val ASSET_PATH = "vad/silero_vad.onnx"

        fun createFromAssetsOrFallback(context: Context): SpeechProbabilityDetector {
            return runCatching {
                val modelBytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = env.createSession(modelBytes, opts)
                SileroOnnxVadDetector(env, session)
            }.getOrElse {
                EnergyFallbackVadDetector()
            }
        }
    }
}

/**
 * Pure-Kotlin adaptive RMS + zero-crossing speech detector used as fallback and in JVM unit tests.
 */
class EnergyFallbackVadDetector : SpeechProbabilityDetector {
    private var noiseFloorRms = 0.004f

    override fun reset() {
        noiseFloorRms = 0.004f
    }

    override fun detectProbability(chunk512: FloatArray): Float {
        if (chunk512.isEmpty()) return 0f
        var sumSq = 0.0
        var zeroCrossings = 0
        var prev = chunk512[0]
        for (s in chunk512) {
            sumSq += s * s
            if ((s >= 0f && prev < 0f) || (s < 0f && prev >= 0f)) {
                zeroCrossings++
            }
            prev = s
        }
        val rms = sqrt(sumSq / chunk512.size).toFloat()
        if (rms < noiseFloorRms * 1.4f) {
            noiseFloorRms = (0.96f * noiseFloorRms + 0.04f * rms).coerceIn(0.0015f, 0.03f)
        }
        val snrRatio = rms / noiseFloorRms.coerceAtLeast(0.0015f)
        val zcrRate = zeroCrossings.toFloat() / chunk512.size
        val zcrPenalty = if (zcrRate > 0.45f) 0.65f else 1.0f
        val rawScore = ((snrRatio - 1.8f) / 4.2f) * zcrPenalty
        return if (abs(rms) < 0.003f) 0f else rawScore.coerceIn(0f, 1f)
    }
}
