package com.freespeak.engine.whisper

object WhisperNative {
    private var libraryLoaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary("freespeak_whisper")
            libraryLoaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun initContext(modelPath: String): Long

    external fun freeContext(contextPtr: Long)

    external fun isMultilingual(contextPtr: Long): Boolean

    external fun transcribe(
        contextPtr: Long,
        pcm16k: FloatArray,
        numThreads: Int,
        language: String,
        initialPrompt: String?,
        translateToEnglish: Boolean = false,
    ): String

    external fun getDetectedLanguage(contextPtr: Long): String

    external fun getSystemInfo(): String
}
