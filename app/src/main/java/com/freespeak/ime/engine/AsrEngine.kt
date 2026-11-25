package com.freespeak.ime.engine

import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import java.io.File
import com.freespeak.ime.text.ChineseOutputMode
import com.freespeak.ime.model.canHintLanguage
import com.freespeak.ime.model.canTranslateToEnglish

data class AsrTranscriptionResult(
    val text: String,
    val detectedLanguage: String? = null,
    val inferenceTimeMs: Long = 0L,
)

data class AsrInferenceOptions(
    val language: String = "auto",
    val numThreads: Int = 4,
    val enableStylePrompt: Boolean = true,
    val translateToEnglish: Boolean = false,
    val chineseOutputMode: ChineseOutputMode = ChineseOutputMode.SYSTEM,
    val targetLanguage: String? = null,
)

fun AsrInferenceOptions.forModel(model: ModelManifest): AsrInferenceOptions = copy(
    language = if (model.canHintLanguage) language else "auto",
    translateToEnglish = translateToEnglish && model.canTranslateToEnglish,
    targetLanguage = targetLanguage.takeIf { model.engineType == EngineType.CANARY_ONNX },
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
