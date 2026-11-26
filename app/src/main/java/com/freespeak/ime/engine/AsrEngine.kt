package com.freespeak.ime.engine

import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import java.io.File
import com.freespeak.ime.text.ChineseOutputMode
import com.freespeak.ime.model.canHintLanguage
import com.freespeak.ime.model.isCanary
import com.freespeak.ime.model.hasRumbleFormatting
import com.freespeak.ime.model.RumbleFormatting

data class AsrTranscriptionResult(
    val text: String,
    val detectedLanguage: String? = null,
    val inferenceTimeMs: Long = 0L,
)

data class AsrInferenceOptions(
    val language: String = "auto",
    val numThreads: Int = 4,
    val chineseOutputMode: ChineseOutputMode = ChineseOutputMode.SYSTEM,
    val targetLanguage: String? = null,
    val rumbleFormatting: RumbleFormatting? = null,
    val fluidCleanMode: Boolean = false,
)

fun AsrInferenceOptions.forModel(model: ModelManifest): AsrInferenceOptions = copy(
    language = if (model.canHintLanguage) language else "auto",
    targetLanguage = targetLanguage.takeIf { model.engineType.isCanary },
    rumbleFormatting = rumbleFormatting.takeIf { model.hasRumbleFormatting },
    fluidCleanMode = fluidCleanMode && model.engineType == EngineType.FLUID_TDT,
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
