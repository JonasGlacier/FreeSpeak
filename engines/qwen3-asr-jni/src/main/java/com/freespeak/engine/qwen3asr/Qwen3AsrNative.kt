package com.freespeak.engine.qwen3asr

object Qwen3AsrNative {
    private var libraryLoaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("freespeak_qwen3asr")
            libraryLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun selectNextTokenGreedy(
        logitsArr: FloatArray,
        logitsOffset: Int,
        vocabSize: Int,
        generatedTokens: IntArray?,
        suppressTokens: IntArray?,
        repetitionPenalty: Float = 1.08f,
    ): Int

    external fun spliceMultimodalEmbeddings(
        prefixEmbeds: FloatArray?,
        audioEmbeds: FloatArray?,
        suffixEmbeds: FloatArray?,
    ): FloatArray

    external fun ctcGreedyDecodeIds(
        logitsFlat: FloatArray,
        timeSteps: Int,
        vocabSize: Int,
        blankId: Int = 0,
    ): IntArray
}
