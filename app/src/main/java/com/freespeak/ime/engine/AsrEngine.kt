package com.freespeak.ime.engine

import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import java.io.File

data class AsrTranscriptionResult(
    val text: String,
    val detectedLanguage: String? = null,
    val inferenceTimeMs: Long = 0L,
)

data class AsrInferenceOptions(
    val language: String = "auto",
    val numThreads: Int = 4,
    val enableStylePrompt: Boolean = true,
)

interface AsrEngine {
    val engineType: EngineType
    val isLoaded: Boolean

    suspend fun load(
        modelDir: File,
        manifest: ModelManifest,
        numThreads: Int,
    ): Result<Unit>

    suspend fun transcribe(
        pcm16k: FloatArray,
        options: AsrInferenceOptions,
    ): Result<AsrTranscriptionResult>

    fun release()
}
