package com.freespeak.ime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VadSilenceFilterTest {

    @Test
    fun `pure silence is rejected without triggering ASR inference`() {
        val detector = EnergyFallbackVadDetector()
        val filter = VadSilenceFilter(detector = detector, speechThreshold = 0.45f)

        val twoSecondsSilence = FloatArray(16000 * 2) { 0.0005f }
        filter.feedSamples(twoSecondsSilence)
        val result = filter.finish()

        assertFalse(result.hasVoiceActivity)
        assertEquals(0, result.samples.size)
        assertTrue(result.rawDurationMs >= 1950L)
    }

    @Test
    fun `mid-utterance long dead air is compressed without splitting sentence`() {
        // Deterministic mock detector: voiced when chunk peak > 0.1f
        val mockDetector = object : SpeechProbabilityDetector {
            override fun reset() {}
            override fun detectProbability(chunk512: FloatArray): Float {
                return if (chunk512.any { it > 0.1f }) 0.95f else 0.02f
            }
        }
        val filter = VadSilenceFilter(
            detector = mockDetector,
            speechThreshold = 0.45f,
            preRollFrames = 4,
            maxRetainedSilenceFrames = 10,
        )

        // 20 frames leading silence + 12 frames Clause 1 + 40 frames long pause + 12 frames Clause 2 + 20 frames trailing silence
        val silenceChunk = FloatArray(512) { 0f }
        val voicedChunk = FloatArray(512) { i -> (0.4 * sin(2.0 * PI * 300.0 * i / 16000.0)).toFloat() }

        repeat(20) { filter.feedSamples(silenceChunk) }
        repeat(12) { filter.feedSamples(voicedChunk) }
        repeat(40) { filter.feedSamples(silenceChunk) }
        repeat(12) { filter.feedSamples(voicedChunk) }
        repeat(20) { filter.feedSamples(silenceChunk) }

        val result = filter.finish()
        assertTrue(result.hasVoiceActivity)

        // Expected retained frames: 4 pre-roll + 12 clause1 + 10 natural pause + 12 clause2 + 6 post-roll = 44 frames
        val actualFrames = result.samples.size / 512
        assertEquals(44, actualFrames)
        assertTrue("Filtered audio must be shorter than raw audio", result.speechDurationMs < result.rawDurationMs)
    }
}
