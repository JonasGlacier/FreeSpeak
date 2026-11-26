package com.freespeak.engine.dsp

object AudioDspNative {
    private var libraryLoaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("freespeak_dsp")
            libraryLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Extracts 80-dim Kaldi Mel-Fbank + optional LFR (e.g. m=7, n=6 -> 560-dim) + optional CMVN.
     * Returns row-major FloatArray of shape [numOutFrames, numMels * max(1, lfrM)].
     * [sherpaFunAsrFrontend] uses Hamming windows with reflected edges and unpadded LFR stacking.
     */
    external fun extractKaldiFbankLfrCmvn(
        pcm16k: FloatArray,
        numMels: Int = 80,
        lfrM: Int = 7,
        lfrN: Int = 6,
        upscaleSamples: Boolean = true,
        cmvnNegMean: FloatArray? = null,
        cmvnInvStd: FloatArray? = null,
        sherpaFunAsrFrontend: Boolean = false,
    ): FloatArray

    /**
     * Extracts 80 or 128-dim Slaney Log-Mel Spectrogram matching Whisper / Qwen3-ASR.
     * Returns channel-first FloatArray of shape [numMels, numFrames].
     */
    external fun extractWhisperMelSpectrogram(
        pcm16k: FloatArray,
        numMels: Int = 128,
        padOrTrimSamples: Int = 0,
    ): FloatArray

    /**
     * Computes normalized RMS level in [0.0f, 1.0f] for waveform UI.
     */
    external fun computeNormalizedRms(
        pcmChunk: FloatArray,
        length: Int = pcmChunk.size,
    ): Float
}
